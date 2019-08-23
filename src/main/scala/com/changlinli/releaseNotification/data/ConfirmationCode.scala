package com.changlinli.releaseNotification.data

import java.util.UUID

import cats.effect.IO
import com.changlinli.releaseNotification.WebServer
import org.http4s.Uri
import org.http4s.Uri.{Authority, Host}

sealed abstract case class ConfirmationCode(str: String) {
  def generateConfirmationUri(hostAddress: Host, hostPort: Int): Uri = {
    // If we have port 80 we drop it from the URL we're creating because it's
    // unnecessary for web browsers
    val hostPortOpt = if (hostPort == 80) None else Some(hostPort)
    Uri(
      authority = Some(Authority(host = hostAddress, port = hostPortOpt)),
      path = s"/${WebServer.confirmationPath}/$str"
    )
  }
}

object ConfirmationCode {
  val generateConfirmationCode: IO[ConfirmationCode] = {
    for {
      uuid <- IO(UUID.randomUUID())
    } yield new ConfirmationCode(uuid.toString) {}
  }

  def unsafeFromString(str: String): ConfirmationCode = new ConfirmationCode(str) {}
}
