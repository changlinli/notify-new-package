package com.changlinli.releaseNotification

import org.http4s.UrlForm

final case class IncomingSubscriptionRequest(packages: List[String], emailAddress: String)

object IncomingSubscriptionRequest {
  def fromUrlForm(urlForm: UrlForm): Either[String, IncomingSubscriptionRequest] = {
    for {
      packages <- urlForm
        .values
        .get("packages")
        .toRight("Packages key not found!")
      emailAddress <- urlForm
        .values.get("emailAddress")
        .flatMap(_.headOption)
        .toRight("Email address key not found!")
    } yield IncomingSubscriptionRequest(packages.toList, emailAddress)
  }
}

