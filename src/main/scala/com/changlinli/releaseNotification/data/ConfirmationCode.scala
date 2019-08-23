package com.changlinli.releaseNotification.data

import java.util.UUID

import cats.effect.IO

sealed abstract case class ConfirmationCode(str: String)

object ConfirmationCode {
  val generateConfirmationCode: IO[ConfirmationCode] = {
    for {
      uuid <- IO(UUID.randomUUID())
    } yield new ConfirmationCode(uuid.toString) {}
  }

  def unsafeFromString(str: String): ConfirmationCode = new ConfirmationCode(str) {}
}
