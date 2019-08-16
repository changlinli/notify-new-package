package com.changlinli.releaseNotification.ids

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

final case class PackageId(toInt: Int)

object PackageId {
  implicit val packageIdEncoder: Encoder[PackageId] = deriveEncoder
  implicit val packageIdDecoder: Decoder[PackageId] = deriveDecoder
}
