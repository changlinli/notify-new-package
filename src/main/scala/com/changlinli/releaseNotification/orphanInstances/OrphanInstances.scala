package com.changlinli.releaseNotification.orphanInstances

import io.circe.{Decoder, Encoder}
import org.http4s.Uri.{Host, Ipv4Address, Ipv6Address, RegName}

object OrphanInstances {

  implicit val uriHostEncoder: Encoder[Host] = Encoder[String].contramap(_.renderString)

  implicit val ipv4Decoder: Decoder[Ipv4Address] =
    Decoder[String].emap(Ipv4Address.fromString(_).left.map(_.message))
  implicit val ipv6Decoder: Decoder[Ipv6Address] =
    Decoder[String].emap(Ipv6Address.fromString(_).left.map(_.message))
  implicit val regDecoder: Decoder[RegName] =
    Decoder[String].map(RegName.apply)

  implicit val hostDecoder: Decoder[Host] = ipv4Decoder
    .map(identity[Host])
    .or(ipv6Decoder.map(identity[Host]))
    .or(regDecoder.map(identity[Host]))


}
