package com.changlinli.releaseNotification.data

import io.circe.{Decoder, Encoder}

final case class PackageName(str: String)

object PackageName {
  implicit val packageNameDecoder: Decoder[PackageName] = Decoder[String].map(PackageName.apply)

  implicit val packageNameEncoder: Encoder[PackageName] = Encoder[String].contramap(_.str)
}
