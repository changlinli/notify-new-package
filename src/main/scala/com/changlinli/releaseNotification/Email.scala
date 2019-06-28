package com.changlinli.releaseNotification

import cats.effect.IO
import com.sendgrid.{Method, Request, SendGrid}
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.Content
import com.sendgrid.helpers.mail.objects.{Email => SGEMail}

class Email(sendGrid: SendGrid) {
  def email(to: String, from: String, subject: String, content: String): IO[Unit] = IO {
    val sendGridContent = new Content("text/plain", content)
    val mail = new Mail(new SGEMail(from), subject, new SGEMail(to), sendGridContent)
    val request = new Request()
    request.setMethod(Method.POST)
    request.setEndpoint("mail/send")
    request.setBody(mail.build())
    val response = sendGrid.api(request)
    println(response.getStatusCode)
    println(response.getBody)
    println(response.getHeaders)
  }
}

object Email {
  val sendGridApiKey = "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"
  def initialize: IO[Email] = IO(new SendGrid(sendGridApiKey)).map(new Email(_))
}
