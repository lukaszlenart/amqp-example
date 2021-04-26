package pl.org.lenart.amqp

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

import java.util.concurrent.ForkJoinPool
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

object Main extends StrictLogging {

  private implicit val system: ActorSystem             = ActorSystem()
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private implicit val ec: ExecutionContext            = ExecutionContext.fromExecutor(new ForkJoinPool(2))

  def main(args: Array[String]): Unit = {
    val config     = ConfigFactory.load()
    val amqpConfig = AmqpConfig.load(config)

    val deliveryService = new DeliveryService(amqpConfig)
    val killSwitch      = new AmqpService(amqpConfig, deliveryService).attachAndListen().run()

    logger.info("Properly started the service")

    sys.addShutdownHook {
      logger.info("Shutting down the service")

      killSwitch.shutdown()

      Try(Await.ready(system.terminate(), 1.seconds)) match {
        case Failure(ex) =>
          logger.warn("Could not terminate Actor System in a given time!", ex)
        case Success(_) =>
          logger.debug("Actor System has been terminated")
      }
    } join 1000 // waits 1 sec for the thread to die
  }

}
