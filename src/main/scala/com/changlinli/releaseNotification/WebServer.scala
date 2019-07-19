package com.changlinli.releaseNotification

import cats.data.{Ior, NonEmptyList}
import cats.effect.{Blocker, ContextShift, Effect, IO, Timer}
import cats.implicits._
import doobie.free.connection.ConnectionIO
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
  final case class SubscribeToPackages(email: EmailAddress, pkgs: NonEmptyList[PackageName]) extends WebAction
  object SubscribeToPackages {
    def fromUrlForm(urlForm: UrlForm): Either[String, SubscribeToPackages] = {
      for {
        packages <- urlForm
          .values
          .get("packages")
          .toRight("Packages key not found!")
          // Manual eta expansion required here because of weird type inference bug
          // Scala 2.13.0
          .map(xs => NonEmptyList.fromFoldable(xs))
          .flatMap(_.toRight("Received an empty list of packages to subscribe to!"))
        emailAddress <- urlForm
          .values.get("emailAddress")
          .flatMap(_.headOption)
          .toRight("Email address key not found!")
      } yield SubscribeToPackages(EmailAddress(emailAddress), packages.map(PackageName.apply))
    }
  }

  sealed trait PersistenceAction

  final case class SubscribeToPackagesFullName(email: EmailAddress, pkgs: NonEmptyList[FullPackage]) extends PersistenceAction

  final case class FullPackage(name: PackageName, homepage: String, anityaId: Int, packageId: Int)

  def emailActionToPersistenceAction(emailAction: EmailAction): PersistenceAction = emailAction match {
    case unsubscribe: UnsubscribeEmailFromPackage => unsubscribe
    case unsubscribeFromAll: UnsubscribeEmailFromAllPackages => unsubscribeFromAll
    case changeEmail: ChangeEmail => changeEmail
  }

  sealed trait Error
  final case class NoPackagesFoundForNames(packageNames: NonEmptyList[PackageName]) extends Error

  def webActionToPersistenceAction(webAction: WebAction)(implicit contextShift: ContextShift[IO]): ConnectionIO[Ior[Error, PersistenceAction]] =
    webAction match {
      case subscribeToPackages: SubscribeToPackages =>
        Persistence.retrievePackages(subscribeToPackages.pkgs)
          .map{
            nameToFullPackages =>
              val nameToFullPackagesOpt = subscribeToPackages
                .pkgs
                .map(name => name -> nameToFullPackages.get(name))
              val firstElem = nameToFullPackagesOpt.head match {
                case (_, Some(fullPackage)) =>
                  Ior.Right(SubscribeToPackagesFullName(subscribeToPackages.email, NonEmptyList.of(fullPackage)))
                case (packageName, None) =>
                  Ior.Left(NoPackagesFoundForNames(NonEmptyList.of(packageName)))
              }
              nameToFullPackagesOpt.foldLeft(firstElem){
                case (Ior.Both(noPackagesFoundForNames, subscribeToPackagesFullName), (_, Some(fullPackage))) =>
                  Ior.Both(noPackagesFoundForNames, subscribeToPackagesFullName.copy(pkgs = fullPackage :: subscribeToPackagesFullName.pkgs))
                case (Ior.Both(noPackagesFoundForNames, subscribeToPackagesFullName), (packageName, None)) =>
                  Ior.Both(noPackagesFoundForNames.copy(packageNames = packageName :: noPackagesFoundForNames.packageNames), subscribeToPackagesFullName)
                case (Ior.Right(subscribeToPackagesFullName), (_, Some(fullPackage))) =>
                  Ior.Right(subscribeToPackagesFullName.copy(pkgs = fullPackage :: subscribeToPackagesFullName.pkgs))
                case (Ior.Right(subscribeToPackagesFullName), (packageName, None)) =>
                  Ior.Both(NoPackagesFoundForNames(NonEmptyList.of(packageName)), subscribeToPackagesFullName)
                case (Ior.Left(noPackagesFoundForNames), (_, Some(fullPackage))) =>
                  Ior.Both(noPackagesFoundForNames, SubscribeToPackagesFullName(subscribeToPackages.email, NonEmptyList.of(fullPackage)))
                case (Ior.Left(noPackagesFoundForNames), (packageName, None)) =>
                  Ior.Left(noPackagesFoundForNames.copy(packageNames = packageName :: noPackagesFoundForNames.packageNames))
              }
          }
    }

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
        response <- SubscribeToPackages.fromUrlForm(form) match {
          case Left(errMsg) =>
            BadRequest(errMsg)
          case Right(incomingSubscription) =>
            IO(logger.info(s"Persisting the following subscription: $incomingSubscription"))
              .>>{
                webActionToPersistenceAction(incomingSubscription)
                  .transact(Persistence.transactor)
                  .flatMap{ errorIorPersistenceAction => errorIorPersistenceAction.traverse(Persistence.processAction) }
                  .flatMap{
                    case Ior.Both(NoPackagesFoundForNames(packageNames), _) =>
                      val successfullyPersistedPackages =
                        incomingSubscription
                          .pkgs
                          .toList
                          .toSet
                          .--(packageNames.toList.toSet)
                          .toList
                          .|>(xs => NonEmptyList.fromFoldable(xs))
                      successfullyPersistedPackages
                        .fold(().pure[IO]){
                          emailSuccessfullySubscribedPackages(
                            emailSender,
                            incomingSubscription.email,
                            _
                          )
                        }
                        .>>(Ok("Successfully submitted form! (with some errors)"))
                    case Ior.Left(err) =>
                      BadRequest("You failed!")
                    case Ior.Right(_) =>
                      emailSuccessfullySubscribedPackages(
                        emailSender,
                        incomingSubscription.email,
                        incomingSubscription.pkgs
                      ) >> Ok("Successfully submitted form!")
                  }
              }
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

  private def emailSuccessfullySubscribedPackages(
    emailSender: Email,
    emailAddress: EmailAddress,
    packages: NonEmptyList[PackageName]
  ): IO[Unit] = {
    emailSender.email(
      to = emailAddress,
      subject = s"Signed for notifications about ${packages.map(_.str).mkString(",")}",
      content = s"You will get an email anytime one of the following packages gets a new version: ${packages.map(_.str).mkString(",")}"
    )
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
