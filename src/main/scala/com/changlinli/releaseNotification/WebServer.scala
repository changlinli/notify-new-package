package com.changlinli.releaseNotification

import java.time.Instant

import cats.data._
import cats.effect.{Blocker, ContextShift, Effect, IO, Timer}
import cats.implicits._
import com.changlinli.releaseNotification.data._
import com.changlinli.releaseNotification.errors.{AnityaIdFieldNotValidInteger, EmailAddressIncorrectFormat, EmailAddressKeyNotFound, NoPackagesFoundForAnityaId, NoPackagesSelected, PackagesKeyNotFound, RequestProcessError, SubscribeToPackagesError, SubscriptionAlreadyExists}
import com.changlinli.releaseNotification.ids.AnityaId
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.syntax._
import org.http4s.Uri.{Authority, Host, RegName, Scheme}
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.scalatags._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder

import scala.language.higherKinds

object WebServer extends CustomLogging {

  final case class SubscribeToPackages(email: EmailAddress, pkgs: NonEmptyList[AnityaId])

  object SubscribeToPackages {
    def fromUrlForm(urlForm: UrlForm): Either[SubscribeToPackagesError, SubscribeToPackages] = {
      for {
        packages <- urlForm
          .values
          .get("packages")
          .toRight(PackagesKeyNotFound)
          // Manual eta expansion required here because of weird type inference bug
          // Scala 2.13.0
          .map(xs => NonEmptyList.fromFoldable(xs))
          .flatMap(_.toRight(NoPackagesSelected))
          .flatMap(_.traverse(x => x.toIntOption.toRight(AnityaIdFieldNotValidInteger(x))))
        emailAddress <- urlForm
          .values
          .get("emailAddress")
          .flatMap(_.headOption)
          .toRight(EmailAddressKeyNotFound)
          .flatMap(candidateEmail => EmailAddress.fromString(candidateEmail).toRight(EmailAddressIncorrectFormat(candidateEmail)))
      } yield SubscribeToPackages(emailAddress, packages.map(AnityaId.apply))
    }
  }

  def getFullPackagesFromSubscribeToPackages(
    email: EmailAddress,
    anityaIds: NonEmptyList[AnityaId]
  ): ConnectionIO[Ior[NonEmptyList[NoPackagesFoundForAnityaId], NonEmptyList[FullPackage]]] = {
    Persistence.retrievePackages(anityaIds)
      .map{
        anityaIdToFullPackage =>
          val anityaIdToFullPackageOpt = anityaIds
            .map(anityaId => anityaId -> anityaIdToFullPackage.get(anityaId))
          val firstElem = anityaIdToFullPackageOpt.head match {
            case (_, Some(fullPackage)) =>
              Ior.Right(NonEmptyList.of(fullPackage))
            case (anityaId, None) =>
              Ior.Left(NonEmptyList.of(NoPackagesFoundForAnityaId(anityaId)))
          }
          anityaIdToFullPackageOpt.tail.foldLeft(firstElem){
            case (Ior.Both(noPackagesFound, subscribeToPackagesFullName), (_, Some(fullPackage))) =>
              Ior.Both(noPackagesFound, fullPackage :: subscribeToPackagesFullName)
            case (Ior.Both(noPackagesFound, subscribeToPackagesFullName), (anityaId, None)) =>
              Ior.Both(NoPackagesFoundForAnityaId(anityaId) :: noPackagesFound, subscribeToPackagesFullName)
            case (Ior.Right(subscribeToPackagesFullName), (_, Some(fullPackage))) =>
              Ior.Right(fullPackage :: subscribeToPackagesFullName)
            case (Ior.Right(subscribeToPackagesFullName), (anityaId, None)) =>
              Ior.Both(NonEmptyList.of(NoPackagesFoundForAnityaId(anityaId)), subscribeToPackagesFullName)
            case (Ior.Left(noPackagesFound), (_, Some(fullPackage))) =>
              Ior.Both(noPackagesFound, NonEmptyList.of(fullPackage))
            case (Ior.Left(noPackagesFound), (anityaId, None)) =>
              Ior.Left(NoPackagesFoundForAnityaId(anityaId) :: noPackagesFound)
          }
      }
  }

  def zipAnityaIdsWithUnsubscribeCodes(anityaIds: NonEmptyList[AnityaId]): IO[NonEmptyList[(AnityaId, UnsubscribeCode)]] = {
    anityaIds.traverse(anityaId => UnsubscribeCode.generateUnsubscribeCode.map((anityaId, _)))
  }

  def processDatabaseResponse(
    email: EmailAddress,
    resultOfSubscribe: Ior[NonEmptyList[RequestProcessError], SuccessfulSubscriptionResult],
    emailSender: EmailSender,
    publicSiteName: Authority
  ): IO[Response[IO]] = {
    resultOfSubscribe match {
      case Ior.Both(err, SuccessfulSubscriptionResult(pkgsWithUnsubscribeCodes, confirmationCode)) =>
        val emailAction = emailSubscriptionConfirmationRequest(
          emailSender,
          email,
          pkgsWithUnsubscribeCodes.map(singleSubscription => (singleSubscription.pkg, singleSubscription.unsubscribeCode)),
          confirmationCode,
          publicSiteName
        )
        IO(err.map(logger.warn("We saw the following error by a user (while other actions were successful)", _)))
          .>>(emailAction)
          .>>(Ok(HtmlGenerators.submittedFormWithSomeErrors(pkgsWithUnsubscribeCodes.map(_.pkg), err)))
      case Ior.Left(err) =>
        val (alreadyExists, _) = RequestProcessError.splitErrors(err.toList)
        val emailAction = NonEmptyList.fromList(alreadyExists).fold(IO.unit){
          emailSubscriptionAlreadyExistsConfirmationRequest(
            emailSender,
            email,
            _,
            publicSiteName
          )
        }
        IO(err.map(logger.warn("We saw the following error by a user", _)))
          .>>(emailAction)
          // Note that we're not going to let a malicious user find out what
          // packages an email address is subscribed to, so we're just
          // returning an OK that seems to indicate everything was fine
          .>>(Ok(HtmlGenerators.submittedFormWithErrors(err)))
      case Ior.Right(SuccessfulSubscriptionResult(pkgsWithUnsubscribeCodes, confirmationCode)) =>
        val emailAction = emailSubscriptionConfirmationRequest(
          emailSender,
          email,
          pkgsWithUnsubscribeCodes.map(singleSubscription => (singleSubscription.pkg, singleSubscription.unsubscribeCode)),
          confirmationCode,
          publicSiteName
        )
        emailAction.>>(Ok(HtmlGenerators.successfullySubmittedFrom(pkgsWithUnsubscribeCodes.map(_.pkg))))
    }
  }

  def fullySubscribeToPackages(
    email: EmailAddress,
    anityaIdsWithUnsubscribeCodes: NonEmptyList[(AnityaId, UnsubscribeCode)],
    currentTime: Instant,
    confirmationCode: ConfirmationCode
  ): ConnectionIO[Ior[NonEmptyList[RequestProcessError], SuccessfulSubscriptionResult]] = {
    val unsubscribeCodes = anityaIdsWithUnsubscribeCodes.map(_._2)
    val getFullPackages = getFullPackagesFromSubscribeToPackages(
      email,
      anityaIdsWithUnsubscribeCodes.map(_._1)
    )
    IorT(getFullPackages)
      .map(fullPackages => fullPackages.zipWith(unsubscribeCodes)((_, _)))
      .flatMap{
        pkgsWithCode =>
          val subscription = Persistence.subscribeToPackagesFullName(
            email,
            pkgsWithCode,
            currentTime,
            confirmationCode
          )
          IorT(subscription)
            .leftWiden[NonEmptyList[RequestProcessError]]
            .as(pkgsWithCode)
            .map(_.map{
              case (pkg, unsubscribeCode) => SuccessfulSinglePackageSubscription(pkg, unsubscribeCode)
            })
      }
      .map(SuccessfulSubscriptionResult(_, confirmationCode))
      .value
  }

  def redirectIncomingEmail(emailSender: EmailSender, adminEmailAddress: EmailAddress, request: Request[IO]): IO[Unit] = {
    logger.info(s"Processing incoming email: $request")
    request.as[String].flatMap{
      emailToAdmin(emailSender, adminEmailAddress, _)
    }
  }

  def staticRoutes(blocker: Blocker)(implicit contextShift: ContextShift[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO]{
      case request @ GET -> Root =>
        StaticFile
          .fromResource("/index.html", blocker, Some(request))
          .getOrElseF(NotFound("Couldn't find index.html!"))
      case request @ GET -> Root / "style.css" =>
        StaticFile
          .fromResource("/style.css", blocker, Some(request))
          .getOrElseF(NotFound("Couldn't find style.css!"))
      case request @ GET -> Root / "faq" =>
        StaticFile
          .fromResource("/faq.html", blocker, Some(request))
          .getOrElseF(NotFound("Couldn't find faq.html!"))
    }

  // If you see a warning here about unreachable code see https://github.com/scala/bug/issues/11457
  def webService(
    emailSender: EmailSender,
    blocker: Blocker,
    transactor: Transactor[IO],
    publicSiteName: Authority,
    bindingAddress: Host,
    bindingPort: Int,
    adminEmailAddress: EmailAddress
  )(implicit contextShift: ContextShift[IO]
  ): HttpRoutes[IO] = HttpRoutes.of[IO]{
    case request @ POST -> Root / "submitEmailAddress" =>
      for {
        form <- request.as[UrlForm]
        _ <- infoIO(s"We got this form: $form")
        response <- SubscribeToPackages.fromUrlForm(form) match {
          case Left(errMsg) =>
            BadRequest(HtmlGenerators.dealWithEmailSubmissionError(errMsg))
          case Right(incomingSubscription) =>
            for {
              _ <- infoIO(s"Persisting the following subscription: $incomingSubscription")
              packagesWithAnityaIds <- zipAnityaIdsWithUnsubscribeCodes(incomingSubscription.pkgs)
              currentTime <- IO(Instant.now())
              confirmationCode <- ConfirmationCode.generateConfirmationCode
              resultOfSubscription <- fullySubscribeToPackages(incomingSubscription.email, packagesWithAnityaIds, currentTime, confirmationCode)
                .transact(transactor)
              response <- processDatabaseResponse(incomingSubscription.email, resultOfSubscription, emailSender, publicSiteName)
            } yield response
        }
      } yield response
    case request @ GET -> Root / "incomingEmail" =>
      Ok(s"Yep this email hook is responding to GET requests! The request headers looked like $request")
    case request @ POST -> Root / "incomingEmail" =>
      redirectIncomingEmail(emailSender, adminEmailAddress, request).>>(Ok(s"Redirected email!"))
    case GET -> Root / subscribePathComponent / code if subscribePathComponent == unsubscribePath =>
      val unsubscribeCode = UnsubscribeCode.unsafeFromString(code)
      Persistence
        .retrievePackageAssociatedWithCode(unsubscribeCode)
        .transact(transactor)
        .map{packageOpt => packageOpt.map(HtmlGenerators.unsubscribePage(_, unsubscribeCode))}
        .flatMap{
          case Some(htmlPage) => Ok(htmlPage)
          case None => Ok(s"There doesn't seem to be a package associated with the unsubscribe code ${unsubscribeCode.str}")
        }
    case POST -> Root / unsubscribePathComponent / code if unsubscribePathComponent == unsubscribePath =>
      Persistence
        .unsubscribeUsingCode(UnsubscribeCode.unsafeFromString(code))
        .transact(transactor)
        .|>(OptionT.apply)
        .flatMap{
          case (unsubscribedPackage, emailAddress) =>
            for {
              _ <- OptionT.liftF(
                emailSuccessfullyUnsubscribedFromPackage(
                  emailSender,
                  emailAddress,
                  unsubscribedPackage,
                )
              )
              response <- OptionT.liftF(
                Ok(HtmlGenerators.unsubcribeConfirmation(unsubscribedPackage))
              )
            } yield response
        }
        .getOrElseF(Ok(s"There doesn't seem to be a package associated with the unsubscribe code $code..."))
    case GET -> Root / confirmationPathComponent / confirmationCode if confirmationPathComponent == confirmationPath =>
      val fullConfirmationCode = ConfirmationCode.unsafeFromString(confirmationCode)
      Persistence
        .retrieveRelevantSubscriptionInfo(fullConfirmationCode)
        .transact(transactor)
        .map(NonEmptyList.fromList)
        .|>(OptionT.apply)
        .map(_.map(_._1))
        .flatMap{
          pkgs =>
            OptionT.liftF(Ok(HtmlGenerators.queryUserAboutSubscribeConfirmation(pkgs, fullConfirmationCode)))
        }
        .getOrElseF(Ok(s"We didn't find any packages for the confirmation code $confirmationCode"))
    case POST -> Root / confirmationPathComponent / confirmationCode if confirmationPathComponent == confirmationPath =>
      val fullConfirmationCode = ConfirmationCode.unsafeFromString(confirmationCode)
      for {
        currentTime <- IO(Instant.now())
        response <- Persistence
          .confirmSubscription(fullConfirmationCode, currentTime)
          .transact(transactor)
          .map(NonEmptyList.fromList)
          .|>(OptionT.apply)
          .flatMap{
            pkgsUnsubscribeCodeAndEmailAddress =>
              val emailToPkgs = pkgsUnsubscribeCodeAndEmailAddress
                .groupBy{case (_, _, email) => email}
                .view
                .mapValues(_.map{case (pkg, code, _) => (pkg, code)})
                .toList
              if (emailToPkgs.length > 1)
                logger.warn(s"We only expected a unique email address to come back here, but we got more than one: $emailToPkgs")
              val emailAction = emailToPkgs.traverse_{
                case (emailAddress, pkgsAndUnsubscribeCodes) =>
                  emailSuccessfullySubscribedPackages(emailSender, emailAddress, pkgsAndUnsubscribeCodes, publicSiteName)
              }
              val httpResponse = Ok(HtmlGenerators.subscribeConfirmation(pkgsUnsubscribeCodeAndEmailAddress.map(_._1)))
              val fullAction = emailAction.>>(httpResponse)
              OptionT.liftF(fullAction)
          }
          .getOrElseF(Ok(s"This confirmation code doesn't seem to correspond to any subscriptions: $confirmationCode"))
      } yield response
    case GET -> Root / "search" :? SearchQueryMatcher(nameFragment) =>
      Persistence
        .searchForPackagesByNameFragment(nameFragment)
        .transact(transactor)
        .flatMap(packages => Ok(packages.asJson))
  }

  val confirmationPath: String = "confirm"

  val unsubscribePath: String = "unsubscribe"

  object SearchQueryMatcher extends QueryParamDecoderMatcher[String]("name")

  private def printPackageWithUnsubscribe(
    pkg: FullPackage,
    unsubscribeCode: UnsubscribeCode,
    publicSiteName: Authority,
  ): String = {
    val releaseMonitoringLink = Uri(
      scheme = Some(Scheme.https),
      authority = Some(Authority(host = RegName("release-monitoring.org"))),
      path = s"/project/${pkg.anityaId}/"
    )
    s"""
       |Package Name: ${pkg.name.str}
       |Package Homepage: ${pkg.homepage}
       |Current Version: ${pkg.currentVersion.str}
       |release-monitoring.org link (see the bottom of this email): ${releaseMonitoringLink.renderString}
       |Unsubscribe link (see the bottom of this email): ${unsubscribeCode.generateUnsubscribeUri(publicSiteName).renderString}
     """.stripMargin
  }

  private def printPackageWithConfirmationCode(
    pkg: FullPackage,
    unsubscribeCode: UnsubscribeCode,
    publicSiteName: Authority,
    confirmationCode: ConfirmationCode
  ): String = {
    val releaseMonitoringLink = Uri(
      scheme = Some(Scheme.https),
      authority = Some(Authority(host = RegName("release-monitoring.org"))),
      path = s"/project/${pkg.anityaId}/"
    )
    s"""
       |Package Name: ${pkg.name.str}
       |Package Homepage: ${pkg.homepage}
       |Current Version: ${pkg.currentVersion.str}
       |Confirmation Link: ${confirmationCode.generateConfirmationUri(publicSiteName).renderString}
       |release-monitoring.org link (see the bottom of this email): ${releaseMonitoringLink.renderString}
       |Unsubscribe link (see the bottom of this email): ${unsubscribeCode.generateUnsubscribeUri(publicSiteName).renderString}
     """.stripMargin
  }

  private def printPackage(
    pkg: FullPackage,
  ): String = {
    val releaseMonitoringLink = Uri(
      scheme = Some(Scheme.https),
      authority = Some(Authority(host = RegName("release-monitoring.org"))),
      path = s"/project/${pkg.anityaId}/"
    )
    s"""
       |Package Name: ${pkg.name.str}
       |Package Homepage: ${pkg.homepage}
       |Current Version: ${pkg.currentVersion.str}
       |release-monitoring.org link (see the bottom of this email): ${releaseMonitoringLink.renderString}
     """.stripMargin
  }

  private def emailSubscriptionAlreadyExistsConfirmationRequest(
    emailSender: EmailSender,
    emailAddress: EmailAddress,
    packagesWithExistingSubscriptions: NonEmptyList[SubscriptionAlreadyExists],
    publicSiteName: Authority
  ): IO[Unit] = {
    val packagesAlreadySignedUp = packagesWithExistingSubscriptions.filter{err => err.confirmedTime.isDefined}
    val packagesPendingConfirmation = packagesWithExistingSubscriptions.filter{err => err.confirmedTime.isEmpty}
    val alreadySignedUpForMessage = {
      if (packagesAlreadySignedUp.nonEmpty) {
        s"These subscription requests concern packages you've already successfully signed up for:\n\n" +
          s"${packagesAlreadySignedUp
            .map{err => (err.pkg, err.packageUnsubscribeCode)}
            .map{case (p, unsubscribeCode) => printPackageWithUnsubscribe(p, unsubscribeCode, publicSiteName)}
            .mkString("\n\n")}"
      } else {
        ""
      }
    }
    val packagesPendingConfirmMessage = {
      if (packagesPendingConfirmation.nonEmpty) {
        s"These subscriptions concern packages that are still awaiting confirmation (to confirm, open up the confirmation link in a web browser):\n\n" +
          s"${packagesPendingConfirmation
            .map{err => (err.pkg, err.packageUnsubscribeCode, err.confirmationCode)}
            .map{case (p, unsubscribeCode, confirmCode) => printPackageWithConfirmationCode(p, unsubscribeCode, publicSiteName, confirmCode)}
            .mkString("\n\n")
          }"
      } else {
        ""
      }
    }
    val content =
      s"""Hi, we've received a request to sign you up for email updates for some packages.
         |
         |However, it appears that you've already previously sent a request to sign up for email updates for these packages.
         |
         |$alreadySignedUpForMessage
         |$packagesPendingConfirmMessage
         |
         |If that was not you, you can either ignore this email, or send an email to admin@${publicSiteName.host.renderString} if you'd like us to look into someone entering your email address without your permission.
         |
         |In the future if you'd like to unsubscribe to updates for a particular package, simply entering any of those unsubscribe links into your web browser will do the trick.
         |
         |Ultimately this service is built on top of Fedora's Anitya project (release-monitoring.org), so we've included a link to the release-monitoring.org page for this package.
       """.stripMargin
    emailSender.email(
      to = emailAddress,
      subject = s"Request to sign you up for notifications about ${packagesWithExistingSubscriptions.map(_.pkg.name.str).mkString(",")}",
      content = content
    )
  }

  private def emailSubscriptionConfirmationRequest(
    emailSender: EmailSender,
    emailAddress: EmailAddress,
    packages: NonEmptyList[(FullPackage, UnsubscribeCode)],
    confirmationCode: ConfirmationCode,
    publicSiteName: Authority
  ): IO[Unit] = {
    val content =
      s"""Hi, we've received a request to sign you up for email updates for new versions of the following packages:
         |
         |${packages.map{case (p, unsubscribeCode) => printPackageWithUnsubscribe(p, unsubscribeCode, publicSiteName)}.mkString("\n\n")}
         |
         |If this was you, please confirm by opening this page in your web browser: ${confirmationCode.generateConfirmationUri(publicSiteName).renderString}
         |
         |If that was not you, you can either ignore this email, or send an email to admin@${publicSiteName.host.renderString} if you'd like us to look into someone entering your email address without your permission.
         |
         |In the future if you'd like to unsubscribe to updates for a particular package, simply entering any of those unsubscribe links into your web browser will do the trick.
         |
         |Ultimately this service is built on top of Fedora's Anitya project (release-monitoring.org), so we've included a link to the release-monitoring.org page for this package.
       """.stripMargin
    emailSender.email(
      to = emailAddress,
      subject = s"Request to sign you up for notifications about ${packages.map(_._1.name.str).mkString(",")}",
      content = content
    )
  }

  private def emailToAdmin(
    emailSender: EmailSender,
    adminEmailAddress: EmailAddress,
    body: String
  ): IO[Unit] = {
    emailSender.email(
      to = adminEmailAddress,
      subject = s"Received an inbound email from Sendgrid",
      content = s"The body of the incoming email was as follows:\n$body"
    )
  }

  private def emailSuccessfullySubscribedPackages(
    emailSender: EmailSender,
    emailAddress: EmailAddress,
    packages: NonEmptyList[(FullPackage, UnsubscribeCode)],
    publicSiteName: Authority,
  ): IO[Unit] = {
    val content =
      s"""
         |Hi you've signed up for email notifications about new packages!
         |
         |You will get an email any time one of the following packages gets a new version:
         |
         |${packages.map{case (p, unsubscribeCode) => printPackageWithUnsubscribe(p, unsubscribeCode, publicSiteName)}.mkString("\n\n")}
         |
         |If you'd like to unsubscribe to updates for a particular package, simply entering any of those unsubscribe links into your web browser will do the trick.
         |
         |I currently haven't implemented the ability to unsubscribe from all packages at once. If you'd like to do so, please just send an email to admin@${publicSiteName.host.renderString}
         |
         |Ultimately this service is built on top of Fedora's Anitya project (release-monitoring.org), so we've included a link to the release-monitoring.org page for this package.
       """.stripMargin
    emailSender.email(
      to = emailAddress,
      subject = s"Signed up for notifications about ${packages.map(_._1.name.str).mkString(",")}",
      content = content
    )
  }

  private def emailSuccessfullyUnsubscribedFromPackage(
    emailSender: EmailSender,
    emailAddress: EmailAddress,
    unsubscribedPackage: FullPackage,
  ): IO[Unit] = {
    val content =
      s"""
         |You have successfully unsubscribed from version updates for the following package:
         |
         |${printPackage(unsubscribedPackage)}
         |
         |Ultimately this service is built on top of Fedora's Anitya project (release-monitoring.org), so we've included a link to the release-monitoring.org page for this package.
       """.stripMargin
    emailSender.email(
      to = emailAddress,
      subject = s"Unsubscribed from version updates for ${unsubscribedPackage.name.str}",
      content = content
    )
  }

  def runWebServer(
    emailSender: EmailSender,
    publicSiteName: Authority,
    port: Int,
    bindingAddress: Host,
    blocker: Blocker,
    transactor: Transactor[IO],
    adminEmailAddress: EmailAddress
  )(implicit timer: Timer[IO],
    contextShift: ContextShift[IO]
  ): IO[Unit] = {
    val allRoutes = Router(
      ("", webService(emailSender, blocker, transactor, publicSiteName, bindingAddress, port, adminEmailAddress)),
      ("", staticRoutes(blocker))
    )
    BlazeServerBuilder[IO]
      .bindHttp(port, bindingAddress.renderString)
      .withHttpApp(allRoutes.orNotFound)
      .serve
      .compile
      .drain
  }


}
