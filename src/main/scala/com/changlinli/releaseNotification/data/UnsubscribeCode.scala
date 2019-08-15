package com.changlinli.releaseNotification.data

import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.UUID

import cats.effect.IO

final case class UnsubscribeCode(str: String)

object UnsubscribeCode {
  val generateUnsubscribeCode: IO[UnsubscribeCode] = {
    for {
      uuid <- IO(UUID.randomUUID())
    } yield UnsubscribeCode(uuid.toString)
  }
}
