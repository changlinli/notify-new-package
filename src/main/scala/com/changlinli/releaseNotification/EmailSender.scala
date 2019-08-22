package com.changlinli.releaseNotification

import cats.effect.IO
import com.changlinli.releaseNotification.data.EmailAddress
import com.sendgrid.{Method, Request, SendGrid}
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.Content
import com.sendgrid.helpers.mail.objects.{Email => SGEMail}
import org.http4s.Uri.Host

class EmailSender(sendGrid: SendGrid, hostAddress: Host) extends CustomLogging {
  private val fullFromAddress = s"notification@${hostAddress.value}"
  def email(to: EmailAddress, subject: String, content: String): IO[Unit] = IO {
    val sendGridContent = new Content("text/plain", content)
    val mail = new Mail(new SGEMail(fullFromAddress), subject, new SGEMail(to.str), sendGridContent)
    val request = new Request()
    request.setMethod(Method.POST)
    request.setEndpoint("mail/send")
    request.setBody(mail.build())
    val response = sendGrid.api(request)
    logger.info(
      s"""
         |Response from sending email to ${to.str}:\n
         |Response Status Code: ${response.getStatusCode}\n
         |Response Body: ${response.getBody}\n
         |Response Headers: ${response.getHeaders}\n
       """.stripMargin
    )
  }
}

object EmailSender {
  val sendGridApiKey = "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"
  def initialize(hostAddress: Host): IO[EmailSender] =
    IO(new SendGrid(sendGridApiKey)).map(new EmailSender(_, hostAddress))
}
