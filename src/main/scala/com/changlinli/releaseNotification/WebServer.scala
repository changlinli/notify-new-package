package com.changlinli.releaseNotification

import cats.data.NonEmptyList
import cats.effect.{Blocker, ContextShift, Effect, IO, Timer}
import cats.implicits._
import doobie.implicits._
import grizzled.slf4j.Logging
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import scala.language.higherKinds

object WebServer extends Logging {

  final case class EmailAddress(str: String)

  final case class PackageName(str: String)

  sealed trait Action
  sealed trait EmailAction extends Action
  final case class UnsubscribeEmailFromPackage(email: EmailAddress, pkg: PackageName) extends EmailAction with PersistenceAction
  final case class UnsubscribeEmailFromAllPackages(email: EmailAddress) extends EmailAction with PersistenceAction
  final case class ChangeEmail(oldEmail: EmailAddress, newEmail: EmailAddress) extends EmailAction with PersistenceAction

  sealed trait WebAction
  final case class SubscribeToPackages(email: Email, pkgs: NonEmptyList[PackageName]) extends WebAction

  sealed trait PersistenceAction

  final case class SubscribeToPackagesFullName(email: Email, pkgs: NonEmptyList[FullPackage]) extends PersistenceAction

  final case class FullPackage(name: PackageName, homepage: String, anityaId: Int)

  def emailActionToPersistenceAction(emailAction: EmailAction): PersistenceAction = ???

  def webActionToPersistenceAction(webAction: WebAction): PersistenceAction = ???

  def processInboundWebhook[F[_] : Effect](request: Request[F]): F[EmailAction] = {
    logger.info(s"Request: $request")
    request.as[String].flatMap{
      body => Effect[F].delay(s"Body: $body")
    }
    Effect[F].delay(UnsubscribeEmailFromAllPackages(EmailAddress("hello")))
  }


  // If you see a warning here about unreachable code see https://github.com/scala/bug/issues/11457
  def webService(
    emailSender: Email,
    blocker: Blocker
  )(implicit contextShift: ContextShift[IO]
  ): HttpRoutes[IO] = HttpRoutes.of[IO]{
    case request @ GET -> Root =>
      StaticFile
        .fromResource("/index.html", blocker, Some(request))
        .getOrElseF(NotFound("Couldn't find index.html!"))
    case GET -> Root / "blah" =>
      Ok("hello blah!")
    case request @ POST -> Root / "submitEmailAddress" =>
      for {
        form <- request.as[UrlForm]
        _ <- IO(logger.info(s"We got this form: $form"))
        response <- IncomingSubscriptionRequest.fromUrlForm(form) match {
          case Left(errMsg) =>
            BadRequest(errMsg)
          case Right(incomingSubscription) =>
            IO(logger.info(s"Persisting the following subscription: $incomingSubscription"))
              .>>(persistSubscriptions(incomingSubscription).transact(Persistence.transactor))
              .>>(emailSender.email(
                to = incomingSubscription.emailAddress,
                subject = s"Signed for notifications about ${incomingSubscription.packages.mkString(",")}",
                content = s"You will get an email anytime one of the following packages gets a new version: ${incomingSubscription.packages.mkString(",")}"
              ))
              .>>(Ok("Successfully submitted form!"))
        }
      } yield response
    case request @ POST -> Root / "incomingEmailHook" =>
      for {
        action <- processInboundWebhook(request)
        persistenceAction = emailActionToPersistenceAction(action)
        _ <- Persistence.processAction(persistenceAction)
        response <- Ok("Processed inbound email!")
      } yield response
  }

  def persistSubscriptions(
    incomingSubscriptionRequest: IncomingSubscriptionRequest
  )(implicit contextShift: ContextShift[IO]
  ): doobie.ConnectionIO[List[Int]] =
    incomingSubscriptionRequest
      .packages
      .traverse(pkg => Persistence.insertIntoDB(name = incomingSubscriptionRequest.emailAddress, packageName = pkg))

  def runWebServer(
    emailSender: Email,
    port: Int,
    ipAddress: String,
    blocker: Blocker
  )(implicit timer: Timer[IO],
    contextShift: ContextShift[IO]
  ): IO[Unit] =
    BlazeServerBuilder[IO]
      .bindHttp(port, ipAddress)
      .withHttpApp(webService(emailSender, blocker).orNotFound)
      .serve
      .compile
      .drain


}
