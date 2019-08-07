package com.changlinli.releaseNotification.data

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

final case class FullPackage(name: PackageName, homepage: String, anityaId: Int, packageId: Int)

object FullPackage {
  implicit val fullPackageDecoder: Decoder[FullPackage] = deriveDecoder
  implicit val fullPackageEncoder: Encoder[FullPackage] = deriveEncoder
}
