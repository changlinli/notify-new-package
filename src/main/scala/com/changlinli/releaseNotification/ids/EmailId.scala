package com.changlinli.releaseNotification.ids

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

final case class EmailId(toInt: Int)

object EmailId {
  implicit val emailIdEncoder: Encoder[EmailId] = deriveEncoder
  implicit val emailIdDecoder: Decoder[EmailId] = deriveDecoder
}
