package pl.org.lenart.amqp

import com.typesafe.scalalogging.StrictLogging
import sttp.client3._
import sttp.client3.akkahttp.AkkaHttpBackend

import scala.concurrent.{ExecutionContext, Future}

class DeliveryService(amqpConfig: AmqpConfig) extends StrictLogging {

  def sendMessage(strMessage: String)(implicit ec: ExecutionContext): Future[DeliveryStatus] = {
    val backend = AkkaHttpBackend()
    basicRequest
      .body(strMessage)
      .post(uri"${amqpConfig.deliveryEndpoint}")
      .readTimeout(amqpConfig.connectionTimout)
      .send(backend)
      .map { response =>
        DeliveryStatus.mapResponse(response.code.code, response.statusText)
      }
      .recover {
        case e: akka.stream.StreamTcpException if isConnectionException(e.getCause) =>
          logger.warn("Got a connection exception when tried send message, trying to redeliver it", e)
          DeliveryStatus.Redeliver
        case e: Throwable =>
          logger.warn("Unsupported exception, re-throwing", e)
          throw e
      }
  }

  // in this way Akka reports connection problems
  private[this] def isConnectionException(cause: Throwable): Boolean = {
    cause != null && cause.isInstanceOf[java.net.ConnectException]
  }

}
