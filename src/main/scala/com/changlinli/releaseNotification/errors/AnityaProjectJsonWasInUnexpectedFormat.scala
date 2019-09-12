package com.changlinli.releaseNotification.errors

import io.circe.{DecodingFailure, Json}

final case class AnityaProjectJsonWasInUnexpectedFormat(json: Json, error: DecodingFailure) extends HumanReadableException {
  override val humanReadableMessage: String =
    s"The JSON passed ($json) in failed to decode properly. We saw the following error: ${error.message}"
}

