package com.changlinli.releaseNotification.data

import java.util.UUID

import cats.effect.IO
import com.changlinli.releaseNotification.WebServer
import org.http4s.Uri
import org.http4s.Uri.{Authority, Scheme}

sealed abstract case class UnsubscribeCode(str: String) {
  def generateUnsubscribeUri(authority: Authority): Uri = {
    // If we have port 80 we drop it from the URL we're creating because it's
    // unnecessary for web browsers
    val hostPortOpt = if (authority.port.contains(80)) None else authority.port
    Uri(
      scheme = Some(Scheme.https),
      authority = Some(authority.copy(port = hostPortOpt)),
      path = s"/${WebServer.unsubscribePath}/$str"
    )
  }
}

object UnsubscribeCode {
  val generateUnsubscribeCode: IO[UnsubscribeCode] = {
    for {
      uuid <- IO(UUID.randomUUID())
    } yield new UnsubscribeCode(uuid.toString) {}
  }

  def unsafeFromString(str: String): UnsubscribeCode = new UnsubscribeCode(str) {}

}
