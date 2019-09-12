package com.changlinli.releaseNotification

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.data._
import cats.effect.{Blocker, ContextShift, Effect, IO, Timer}
import cats.implicits._
import com.changlinli.releaseNotification.data._
import com.changlinli.releaseNotification.ids.{AnityaId, SubscriptionId}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe._
import io.circe.syntax._
import org.http4s.Uri.{Authority, Host, RegName, Scheme}
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.scalatags._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

object WebServer extends CustomLogging {

  sealed trait Action
  sealed trait EmailAction extends Action
  final case class UnsubscribeEmailFromPackage(email: EmailAddress, pkg: PackageName) extends EmailAction with PersistenceAction
  final case class UnsubscribeEmailFromAllPackages(email: EmailAddress) extends EmailAction with PersistenceAction
  final case class ChangeEmail(oldEmail: EmailAddress, newEmail: EmailAddress) extends EmailAction with PersistenceAction

  sealed trait WebAction
  final case class UnsubscribeUsingCode(code: UnsubscribeCode) extends WebAction
  final case class SubscribeToPackages(email: EmailAddress, pkgs: NonEmptyList[AnityaId]) extends WebAction
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
          .flatMap(_.traverse(x => x.toIntOption.toRight(s"Attempted to convert $x into an integer as an AnityaId but it doesn't look like a valid integer!")))
        emailAddress <- urlForm
          .values
          .get("emailAddress")
          .flatMap(_.headOption)
          .toRight("Email address key not found!")
          .flatMap(candidateEmail => EmailAddress.fromString(candidateEmail).toRight(s"$candidateEmail was an invalid email address!"))
      } yield SubscribeToPackages(emailAddress, packages.map(AnityaId.apply))
    }
  }

  sealed trait PersistenceAction

  final case class SubscribeToPackagesFullName(email: EmailAddress, pkgs: NonEmptyList[FullPackage]) extends PersistenceAction

  def emailActionToPersistenceAction(emailAction: EmailAction): PersistenceAction = emailAction match {
    case unsubscribe: UnsubscribeEmailFromPackage => unsubscribe
    case unsubscribeFromAll: UnsubscribeEmailFromAllPackages => unsubscribeFromAll
    case changeEmail: ChangeEmail => changeEmail
  }

  final case class RawAnityaProjectResultPage(
    items: List[RawAnityaProject],
    items_per_page: Int,
    page: Int,
    total_items: Int
  )

  object RawAnityaProjectResultPage {
    import io.circe.generic.semiauto._
    implicit val decodeAnityaProjectResultPage: Decoder[RawAnityaProjectResultPage] = deriveDecoder
    implicit val encodeAnityaProjectResultPage: Encoder[RawAnityaProjectResultPage] = deriveEncoder
  }

  final case class RawAnityaProject(
    backend: String,
    created_on: BigDecimal,
    ecosystem: String,
    homepage: String,
    id: Int,
    name: String,
    regex: Option[String],
    updated_on: BigDecimal,
    version: Option[String],
    version_url: Option[String],
    versions: List[String]
  )

  object RawAnityaProject {
    import io.circe.generic.semiauto._
    implicit val decodeAnityaProject: Decoder[RawAnityaProject] = deriveDecoder
    implicit val encodeAnityaProject: Encoder[RawAnityaProject] = deriveEncoder
  }

  type ErrorTIO[A] = EitherT[IO, RequestProcessError, A]

  type UnexpectedFormatTIO[A] = EitherT[IO, AnityaProjectJsonWasInUnexpectedFormat, A]

  private def liftToErrorT[A](x: IO[A]): ErrorTIO[A] = EitherT.liftF(x)

  def requestProjectByPage(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int,
    currentPageIdx: Int
  )(implicit contextShift: ContextShift[IO]): IO[Either[AnityaProjectJsonWasInUnexpectedFormat, RawAnityaProjectResultPage]] = {
    val requestUrl = anityaPackageEndpoint / "api" / "v2" / "projects" / "" +? ("items_per_page", itemsPerPage) +? ("page", currentPageIdx)
    val request = Request[IO](Method.GET, requestUrl)
    logger.info(s"WE'RE REQUESTING A PROJECT BY PAGE... for this url: $requestUrl")
    val clientRequest = client.fetchAs[String](request)
    implicit val timer: Timer[IO] = ThreadPools.timer
    IO.race(clientRequest, IO.sleep(FiniteDuration(5, TimeUnit.SECONDS)) *> IO.raiseError(new Exception("WEIORAOIWEJROIR")))
      .map(_.fold(identity, identity))
      .flatTap(str => IO(logger.info(s"This is what came out of our request! $str")))
      .attempt
      .flatMap{
        strOrErr =>
          logger.info(s"WETIAWEIOTIAWOEHTAWE: $strOrErr")
          strOrErr
            .fold[IO[String]](IO.raiseError, x => IO(x))
            .map(io.circe.parser.parse)
            .flatMap(_.fold(IO.raiseError, x => IO(x)))
      }
      .attempt
      .flatMap{
        case Right(json) =>
          logger.info(s"Received this JSON from the Anitya web server about packages: $json")
          val decodeResult = Decoder[RawAnityaProjectResultPage].decodeJson(json).leftMap(AnityaProjectJsonWasInUnexpectedFormat(json, _))
          IO(logger.info(s"THIS WAS THE RESULT AFTER DECODING: $decodeResult")) *>
            IO(decodeResult)
        case Left(err) =>
          logger.error("We blew up!", err)
          IO.raiseError(err)
      }
  }

  def requestAllAnityaProjectResultPages(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[Either[AnityaProjectJsonWasInUnexpectedFormat, NonEmptyList[RawAnityaProjectResultPage]]] = {
    val firstCall = requestProjectByPage(client, anityaPackageEndpoint, itemsPerPage, 1)
      .|>(EitherT.apply)
      .map(NonEmptyList.of(_))
    val result = for {
      firstResult <- firstCall
      allPages <- cats.Monad[UnexpectedFormatTIO].iterateWhileM(firstResult){
        pages =>
          val lastPage = pages.head
          val webRequest =
            requestProjectByPage(client, anityaPackageEndpoint, itemsPerPage, lastPage.page + 1)
              .|>(EitherT.apply)
              .map(_ :: pages)
          // To make sure we don't overwhelm the web server
          val sleep =  EitherT.liftF[IO, AnityaProjectJsonWasInUnexpectedFormat, Unit](IO(logger.info("Briefly sleeping to avoid overloading the Anitya server")) *> IO.sleep(FiniteDuration(1, TimeUnit.SECONDS)))
          webRequest <* sleep
      }{ pages =>
        val currentPage = pages.head
        val numOfRemainingItems = currentPage.total_items - currentPage.items_per_page * currentPage.page
        logger.info(s"We have this many packages left to retrieve from Anitya: $numOfRemainingItems")
        numOfRemainingItems > 0
      }
    } yield allPages
    result.value
  }

  def requestAllAnityaProjects(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[Either[AnityaProjectJsonWasInUnexpectedFormat, List[RawAnityaProject]]] = {
    requestAllAnityaProjectResultPages(client, anityaPackageEndpoint, itemsPerPage)
      .|>(EitherT.apply)
      .map(pages => pages.toList.flatMap(page => page.items))
      .value
  }

  trait HumanReadableException extends Exception {
    val humanReadableMessage: String

    override def getMessage: String = humanReadableMessage
  }

  sealed trait RequestProcessError extends HumanReadableException with Product with Serializable {
    val humanReadableMessage: String

    override def getMessage: String = humanReadableMessage
  }
  final case class SubscriptionAlreadyExists(
    subscriptionId: SubscriptionId,
    pkg: FullPackage,
    emailAddress: EmailAddress,
    packageUnsubscribeCode: UnsubscribeCode,
    confirmationCode: ConfirmationCode,
    confirmedTime: Option[Instant]
  ) extends RequestProcessError {
    override val humanReadableMessage: String =
      s"A subscription already exists for the following package ID, package name, subscription ID, and email addresses: " +
        s"Package ID: ${subscriptionId.toInt}, Package name: ${pkg.name}, Subscription ID: ${subscriptionId.toInt}, Email: ${emailAddress.str}"
  }
  final case class NoPackagesFoundForAnityaId(anityaId: AnityaId) extends RequestProcessError {
    override val humanReadableMessage: String =
      s"We were unable to find any packages corresponding to the following anitya ID: ${anityaId.toInt}"
  }

  object RequestProcessError {
    def splitErrors(errors: List[RequestProcessError]): (List[SubscriptionAlreadyExists], List[NoPackagesFoundForAnityaId]) = {
      errors.foldLeft((List.empty[SubscriptionAlreadyExists], List.empty[NoPackagesFoundForAnityaId])){
        case ((subscriptionAlreadyExistsErrs, noPackagesFoundForAnityaIdErrs), newErr) =>
          newErr match {
            case subscriptionAlreadyExistsErr: SubscriptionAlreadyExists =>
              (subscriptionAlreadyExistsErr :: subscriptionAlreadyExistsErrs, noPackagesFoundForAnityaIdErrs)
            case noPackagesFoundErr: NoPackagesFoundForAnityaId =>
              (subscriptionAlreadyExistsErrs, noPackagesFoundErr :: noPackagesFoundForAnityaIdErrs)
          }
      }
    }

  }

  final case class AnityaProjectJsonWasInUnexpectedFormat(json: Json, error: DecodingFailure) extends HumanReadableException {
    override val humanReadableMessage: String =
      s"The JSON passed ($json) in failed to decode properly. We saw the following error: ${error.message}"
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
        IO(err.map(logger.warn("We saw the following error by a user", _)))
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

  def processInboundWebhook[F[_] : Effect](request: Request[F]): F[EmailAction] = {
    logger.info(s"Processing incoming email: $request")
    request.as[String].flatMap{
      body => Effect[F].delay(s"Body: $body")
    }
    Effect[F].delay(UnsubscribeEmailFromAllPackages(EmailAddress.unsafeFromString("hello@hello.com")))
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
            BadRequest(errMsg)
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

  private def emailSomeSubscriptionAlreadyExistsSomeSubscriptionsAreNewConfirmationRequest(
    emailSender: EmailSender,
    emailAddress: EmailAddress,
    packagesWithoutExistingSubscriptions: NonEmptyList[(FullPackage, UnsubscribeCode)],
    packagesWithExistingSubscriptions: NonEmptyList[SubscriptionAlreadyExists],
    confirmationCode: ConfirmationCode,
    publicSiteName: Authority
  ): IO[Unit] = {
    val packagesAlreadySignedUp = packagesWithExistingSubscriptions.filter{err => err.confirmedTime.isDefined}
    val packagesPendingConfirmation = packagesWithExistingSubscriptions.filter{err => err.confirmedTime.isEmpty}

    val alreadySignedUpForMessage = {
      s"Some of these subscription requests concern packages you've already successfully signed up for:\n\n" +
        s"${packagesAlreadySignedUp
          .map{err => (err.pkg, err.packageUnsubscribeCode)}
          .map{case (p, unsubscribeCode) => printPackageWithUnsubscribe(p, unsubscribeCode, publicSiteName)}
          .mkString("\n\n")}"
    }
    val packagesPendingConfirmMessage = {
      s"Some of these subscriptions concern packages that are still awaiting confirmation:\n\n" +
        s"${packagesPendingConfirmation
          .map{err => (err.pkg, err.packageUnsubscribeCode, err.confirmationCode)}
          .map{case (p, unsubscribeCode, confirmCode) => printPackageWithConfirmationCode(p, unsubscribeCode, publicSiteName, confirmCode)}
          .mkString("\n\n")
        }"
    }
    val newPackagesMessages = {
      s"Some of these packages are new requests for subscriptions:\n\n" +
        s"${packagesWithoutExistingSubscriptions
          .map{case (p, unsubscribeCode) => printPackageWithUnsubscribe(p, unsubscribeCode, publicSiteName)}
          .mkString("\n\n")
        }"
    }
    val content =
      s"""Hi, we've received a request to sign you up for email updates for some packages.
         |
         |However, it appears that you've already previously sent a request to sign up for email updates for some of these packages.
         |
         |${if (packagesAlreadySignedUp.nonEmpty) alreadySignedUpForMessage else ""}
         |${if (packagesPendingConfirmation.nonEmpty) packagesPendingConfirmMessage else ""}
         |$newPackagesMessages
         |
         |If these requests were from you, please confirm by opening this page in your web browser: ${confirmationCode.generateConfirmationUri(publicSiteName).renderString}.
         |
         |${
        if (packagesPendingConfirmation.nonEmpty) {
          "Note that this code only confirms you for these new subscriptions! " +
            "If you have any pre-existing subscription requests that have yet to " +
            "be confirmed should be confirmed by entering the links in the above " +
            "listing of packages awaiting confirmation."
        } else {
          ""
        }
      }
         |
         |If that was not you, you can either ignore this email, or send an email to admin@${publicSiteName.host.renderString} if you'd like us to look into someone entering your email address without your permission.
         |
         |In the future if you'd like to unsubscribe to updates for a particular package, simply entering any of those unsubscribe links into your web browser will do the trick.
         |
         |Ultimately this service is built on top of Fedora's Anitya project (release-monitoring.org), so we've included a link to the release-monitoring.org page for this package.
       """.stripMargin
    val allPackageNames = packagesWithExistingSubscriptions
      .map(_.pkg)
      .++(packagesWithoutExistingSubscriptions.map(_._1).toList)
      .map(_.name.str)
    emailSender.email(
      to = emailAddress,
      subject = s"Request to sign you up for notifications about ${allPackageNames.mkString(",")}",
      content = content
    )
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

  def persistSubscriptions(
    incomingSubscriptionRequest: IncomingSubscriptionRequest
  )(implicit contextShift: ContextShift[IO]
  ): doobie.ConnectionIO[List[Int]] =
    incomingSubscriptionRequest
      .packages
      .traverse(pkg => Persistence.insertIntoDB(name = incomingSubscriptionRequest.emailAddress, packageName = pkg))

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
