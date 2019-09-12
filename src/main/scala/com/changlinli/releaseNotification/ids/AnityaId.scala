package com.changlinli.releaseNotification.ids

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

final case class AnityaId(toInt: Int)

object AnityaId {
  implicit val anityaIdEncoder: Encoder[AnityaId] = Encoder[Int].contramap(_.toInt)
  implicit val anityaIdDecoder: Decoder[AnityaId] = Decoder[Int].map(AnityaId.apply)
}
