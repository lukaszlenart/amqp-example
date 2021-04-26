package pl.org.lenart.amqp

import akka.Done
import akka.stream.alpakka.amqp._
import akka.stream.alpakka.amqp.scaladsl.{AmqpFlow, AmqpSource, CommittableReadResult}
import akka.stream.scaladsl._
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import com.typesafe.scalalogging.StrictLogging

import java.util
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class AmqpService(amqpConfig: AmqpConfig, deliveryService: DeliveryService)(
  implicit ec: ExecutionContext,
  materializer: Materializer
) extends StrictLogging {

  private[amqp] def attachAndListen(): RunnableGraph[UniqueKillSwitch] = {
    RestartSource
      .withBackoff(2.seconds, 10.seconds, 0.1) { () =>
        logger.info("Creating a new stream to propagate messages")
        AmqpSource
          .committableSource(
            NamedQueueSourceSettings(
              amqpConfig.amqpConnectionProvider,
              amqpConfig.amqpWorkQueue
            ),
            bufferSize = 30
          )
          .map(postMessage)
      }
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.left)
  }

  private[this] def postMessage(readResult: CommittableReadResult): Future[Unit] = {
    def getMessageText(result: CommittableReadResult): Either[Throwable, String] = {
      Try {
        result.message.bytes.utf8String
      }.toEither
    }

    getMessageText(readResult) match {
      case Left(error) =>
        logger.warn("Got error when extracting message body, rejecting message!", error)
        rejectMessage(readResult)

      case Right(strMessage) =>
        deliveryService
          .sendMessage(strMessage)
          .flatMap { status =>
            handleResponse(readResult, status)
          }
          .recoverWith {
            case e: Throwable =>
              logger.warn("Got exception, rejecting the message", e)
              rejectMessage(readResult)
          }
    }
  }

  private[this] def rejectMessage(readResult: CommittableReadResult): Future[Unit] = {
    val amqpFlow: Flow[WriteMessage, WriteResult, Future[Done]] =
      AmqpFlow.withConfirm(
        AmqpWriteSettings(amqpConfig.amqpConnectionProvider)
          .withExchange(amqpConfig.amqpRejectedExchange)
      )

    RestartSource
      .onFailuresWithBackoff(1.second, 5.seconds, 0.1) { () =>
        Source
          .single(WriteMessage(readResult.message.bytes))
          .via(amqpFlow)
          .map { result =>
            if (result.confirmed) {
              readResult.ack().map { _ =>
                logger.debug(s"Rejected message was moved into ${amqpConfig.amqpRejectedExchange}")
              }
            } else {
              readResult.nack().map { _ =>
                logger.warn("Rejected message was not accepted, re-queueing it!")
              }
            }
          }
      }
      .runWith(Sink.ignore)
      .map { _ =>
        logger.debug(s"Message has been sent into ${amqpConfig.amqpRejectedExchange}!")
      }
  }

  private[this] def handleResponse(
    readResult: CommittableReadResult,
    status: DeliveryStatus
  ): Future[Unit] = {
    status match {
      case DeliveryStatus.Accepted =>
        readResult.ack().map { _ =>
          logger.info(s"Message has been accepted")
        }

      case DeliveryStatus.Declined(reason) =>
        logger.warn(s"Messages has been declined and will be rejected, reason: $reason")
        rejectMessage(readResult)

      case DeliveryStatus.Redeliver if redeliveryCountAccepted(readResult) =>
        readResult.nack(requeue = false).map { _ =>
          logger.info(s"Message need to be redeliver as it is still below redelivery limit!")
        }

      case DeliveryStatus.Redeliver if !redeliveryCountAccepted(readResult) =>
        logger.warn(s"Message overflow the retry limit, rejecting the message!")
        rejectMessage(readResult)

      case DeliveryStatus.Unknown if !readResult.message.envelope.isRedeliver =>
        readResult.nack(requeue = true).map { _ =>
          logger.warn(s"Got unknown status, re-queuing the message!")
        }

      case DeliveryStatus.Unknown if readResult.message.envelope.isRedeliver =>
        logger.warn(
          s"Got unknown status of a message which was already re-queued, dropping the message!"
        )
        rejectMessage(readResult)
    }
  }

  private[this] def redeliveryCountAccepted(readResult: CommittableReadResult): Boolean = {
    val headers = readResult.message.properties.getHeaders
    logger.debug(s"Message headers: $headers")
    if (headers != null && headers.containsKey("x-death")) {
      val xDeath =
        headers.get("x-death").asInstanceOf[util.ArrayList[util.Map[String, AnyRef]]].asScala
      logger.debug(s"x-death header: $xDeath")
      val counter = xDeath.find { header =>
        header.asScala.contains("count")
      } map { header =>
        header.get("count").toString.toInt
      }
      logger.debug(s"Count value $counter")
      counter.getOrElse(0) < amqpConfig.redeliverLimit
    } else {
      true
    }
  }

}
