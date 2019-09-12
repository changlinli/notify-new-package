package com.changlinli.releaseNotification

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

final case class RawAnityaProject(
  backend: String,
  created_on: BigDecimal,
  ecosystem: String,
  homepage: String,
  id: Int,
  name: String,
  regex: Option[String],
  updated_on: BigDecimal,
  version: Option[String],
  version_url: Option[String],
  versions: List[String]
)

object RawAnityaProject {
  implicit val decodeAnityaProject: Decoder[RawAnityaProject] = deriveDecoder
  implicit val encodeAnityaProject: Encoder[RawAnityaProject] = deriveEncoder
}

final case class RawAnityaProjectResultPage(
  items: List[RawAnityaProject],
  items_per_page: Int,
  page: Int,
  total_items: Int
)

object RawAnityaProjectResultPage {
  implicit val decodeAnityaProjectResultPage: Decoder[RawAnityaProjectResultPage] = deriveDecoder
  implicit val encodeAnityaProjectResultPage: Encoder[RawAnityaProjectResultPage] = deriveEncoder
}
