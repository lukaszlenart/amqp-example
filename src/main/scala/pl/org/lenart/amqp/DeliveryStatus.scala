package pl.org.lenart.amqp

import com.typesafe.scalalogging.StrictLogging

sealed trait DeliveryStatus

object DeliveryStatus extends StrictLogging {

  object Accepted                     extends DeliveryStatus
  case class Declined(reason: String) extends DeliveryStatus
  object Redeliver                    extends DeliveryStatus
  object Unknown                      extends DeliveryStatus

  def mapResponse(responseCode: Int, statusText: String): DeliveryStatus = {
    if (isAccepted(responseCode)) {
      logger.debug(s"Request was accepted with response code [$responseCode] and status: $statusText")
      DeliveryStatus.Accepted
    } else if (isDeclined(responseCode)) {
      logger.warn(
        s"Request was declined by delivery endpoint with response code [$responseCode] and status: $statusText"
      )
      DeliveryStatus.Declined(statusText)
    } else if (isRedeliver(responseCode)) {
      logger.info(
        s"Request need to be redeliver as delivery endpoint's response code is [$responseCode] and status: $statusText"
      )
      DeliveryStatus.Redeliver
    } else {
      logger.warn(s"Got unexpected response code [$responseCode] with status: $statusText")
      DeliveryStatus.Unknown
    }
  }

  private[this] def isAccepted(responseCode: Int): Boolean = {
    responseCode == 200 ||
    responseCode == 201 ||
    responseCode == 203 ||
    responseCode == 204
  }

  private[this] def isRedeliver(responseCode: Int): Boolean = {
    responseCode == 408 ||
    responseCode == 425 ||
    responseCode == 429 ||
    responseCode == 500 ||
    (responseCode > 501 && responseCode < 505) ||
    (responseCode > 505 && responseCode < 511)
  }

  private[this] def isDeclined(responseCode: Int): Boolean = {
    (responseCode > 204 && responseCode < 400) ||
    (responseCode >= 400 && responseCode < 408) ||
    (responseCode > 408 && responseCode < 425) ||
    (responseCode > 425 && responseCode < 429) ||
    (responseCode > 429 && responseCode < 500) ||
    responseCode == 501 ||
    responseCode == 505 ||
    responseCode == 511
  }

}
