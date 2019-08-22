package com.changlinli.releaseNotification.data

import io.circe.{Decoder, Encoder}

final case class PackageVersion(str: String)

object PackageVersion {
  implicit val packageVersionDecoder: Decoder[PackageVersion] = Decoder[String].map(PackageVersion.apply)

  implicit val packageVersionEncoder: Encoder[PackageVersion] = Encoder[String].contramap(_.str)
}
