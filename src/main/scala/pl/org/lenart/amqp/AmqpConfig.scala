package pl.org.lenart.amqp

import akka.stream.alpakka.amqp._
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import java.net.InetAddress
import scala.concurrent.duration.Duration

class AmqpConfig private (config: Config) extends StrictLogging {

  private val amqpHost = config.getString("amqp.host")
  private val amqpPort = config.getInt("amqp.port")

  val amqpConnectionProvider: AmqpConnectionProvider = {
    val localName = InetAddress.getLocalHost.getHostName

    logger.debug(s"Creating connection to AMQP service using [$amqpHost:$amqpPort] from [$localName]")

    AmqpCachedConnectionProvider(
      AmqpDetailsConnectionProvider(amqpHost, amqpPort)
        .withConnectionName(s"amqp-service at $localName")
        // see https://github.com/akka/alpakka/issues/1270
        .withAutomaticRecoveryEnabled(false)
        .withTopologyRecoveryEnabled(false)
    )
  }

  val amqpWorkQueue: String        = config.getString("amqp.work-queue-name")
  val amqpRejectedExchange: String = config.getString("amqp.rejected-exchange-name")

  val deliveryEndpoint: String   = config.getString("amqp.delivery-endpoint")
  val redeliverLimit: Int        = config.getInt("amqp.redelivery-limit")
  val connectionTimout: Duration = Duration.fromNanos(config.getDuration("amqp.connection-timout").toNanos)
}

object AmqpConfig {
  def load(config: Config): AmqpConfig = {
    new AmqpConfig(config)
  }
}
