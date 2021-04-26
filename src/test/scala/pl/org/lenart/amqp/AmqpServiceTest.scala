package pl.org.lenart.amqp

import akka.actor.ActorSystem
import akka.stream.alpakka.amqp.scaladsl.{AmqpSink, AmqpSource, CommittableReadResult}
import akka.stream.alpakka.amqp.{AmqpWriteSettings, NamedQueueSourceSettings}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import com.dimafeng.testcontainers.{Container, ForAllTestContainer, SingleContainer}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.mockito.IdiomaticMockito
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.RabbitMQContainer

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class AmqpServiceTest
    extends TestKit(ActorSystem("Test"))
    with AnyFlatSpecLike
    with IdiomaticMockito
    with Matchers
    with ForAllTestContainer
    with LazyLogging {

  implicit private val ec: ExecutionContext       = ExecutionContext.global
  implicit private val materializer: Materializer = ActorMaterializer()

  private val workQueueName        = "test.amqp.work-queue"
  private val redeliveryQueueName  = "test.amqp.redelivery-queue"
  private val rejectedQueueName    = "test.amqp.rejected-queue"
  private val rejectedExchangeName = "test.amqp.rejected-exchange"

  private val rabbitMQContainer = new RabbitMQContainer("rabbitmq:3.7.8-management")
    .withQueue(
      workQueueName,
      false,
      true,
      Map(
        "x-dead-letter-routing-key" -> redeliveryQueueName.asInstanceOf[AnyRef],
        "x-dead-letter-exchange"    -> "".asInstanceOf[AnyRef]
      ).asJava
    )
    .withQueue(redeliveryQueueName)
    .withQueue(rejectedQueueName)
    .withExchange(rejectedExchangeName, "fanout")
    .withBinding(rejectedExchangeName, rejectedQueueName)

  override val container: Container = new SingleContainer[RabbitMQContainer]() {
    override implicit val container: RabbitMQContainer = rabbitMQContainer
  }

  private var config: AmqpConfig = _

  override def afterStart(): Unit = {
    logger.info(s"Management console available at: ${rabbitMQContainer.getHttpUrl}")

    config = AmqpConfig.load(
      ConfigFactory.parseMap(
        Map(
          "amqp.host"                   -> rabbitMQContainer.getHost,
          "amqp.port"                   -> rabbitMQContainer.getAmqpPort,
          "amqp.work-queue-name"        -> workQueueName,
          "amqp.rejected-exchange-name" -> rejectedExchangeName,
          "amqp.delivery-endpoint"      -> "http://localhost:8080",
          "amqp.redelivery-limit"       -> 2,
          "amqp.connection-timout"      -> 2
        ).asJava
      )
    )
  }

  // test source connects to a redeliver queue
  private lazy val testRedeliveryQueue = AmqpSource
    .committableSource(
      NamedQueueSourceSettings(
        config.amqpConnectionProvider,
        redeliveryQueueName
      ),
      1
    )
    .map(handleRedeliveryMessage)

  // test source connects to a rejected queue
  private lazy val testRejectedQueue = AmqpSource
    .committableSource(
      NamedQueueSourceSettings(
        config.amqpConnectionProvider,
        rejectedQueueName
      ),
      1
    )
    .map(handleRejectedMessage)

  private def handleRedeliveryMessage(message: CommittableReadResult): Either[Throwable, String] = {
    Try {
      val strMessage = message.message.bytes.utf8String
      logger.info(s"Got message to be redelivered: $strMessage")
      strMessage
    }.toEither
  }

  private def handleRejectedMessage(message: CommittableReadResult): Either[Throwable, String] = {
    Try {
      val strMessage = message.message.bytes.utf8String
      logger.info(s"Got message to be rejected: $strMessage")
      strMessage
    }.toEither
  }

  private val deliveryService: DeliveryService = mock[DeliveryService]

  it should "re-deliver message" in {
    // given
    val message = "Test message"
    deliveryService.sendMessage(message) shouldReturn Future.successful(DeliveryStatus.Redeliver)

    // when
    val service    = new AmqpService(config, deliveryService).attachAndListen()
    val killSwitch = service.run()

    sendTestMessage(message)

    // then
    val testProbe = TestProbe()
    testRedeliveryQueue.to(Sink.actorRef(testProbe.ref, onCompleteMessage = "completed")).run()

    testProbe.expectMsg(1.seconds, Right(message))
    testProbe.expectNoMessage(2.seconds)

    killSwitch.shutdown()
  }

  it should "reject message" in {
    // given
    val message = "Test message"
    deliveryService.sendMessage(message) shouldReturn Future.successful(DeliveryStatus.Declined("Reject!"))

    // when
    val service    = new AmqpService(config, deliveryService).attachAndListen()
    val killSwitch = service.run()

    sendTestMessage(message)

    // then
    val testProbe = TestProbe()
    testRejectedQueue.to(Sink.actorRef(testProbe.ref, onCompleteMessage = "completed")).run()

    testProbe.expectMsg(1.seconds, Right(message))
    testProbe.expectNoMessage(2.seconds)

    killSwitch.shutdown()
  }

  it should "reject message if delivery service returns failed future" in {
    // given
    val message = "Boom!!!"
    deliveryService.sendMessage(message) shouldReturn Future.failed(new RuntimeException("Oops!"))

    // when
    val service    = new AmqpService(config, deliveryService).attachAndListen()
    val killSwitch = service.run()

    sendTestMessage(message)

    // then
    val testProbe = TestProbe()
    testRejectedQueue.to(Sink.actorRef(testProbe.ref, onCompleteMessage = "completed")).run()

    testProbe.expectMsg(1.seconds, Right(message))
    testProbe.expectNoMessage(2.seconds)

    killSwitch.shutdown()
  }

  private[this] def sendTestMessage(request: String) = {
    val amqpSink = AmqpSink.simple(
      AmqpWriteSettings(config.amqpConnectionProvider).withRoutingKey(workQueueName)
    )
    val result = Source
      .single(ByteString(request))
      .runWith(amqpSink)

    Await.ready(result, 5.seconds)
  }

}
