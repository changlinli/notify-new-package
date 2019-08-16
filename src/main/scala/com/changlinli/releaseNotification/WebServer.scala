package com.changlinli.releaseNotification

import java.util.concurrent.TimeUnit

import cats.data.{EitherT, Ior, IorT, NonEmptyList}
import cats.effect.{Blocker, ContextShift, Effect, IO, Timer}
import cats.implicits._
import cats.kernel.Semigroup
import com.changlinli.releaseNotification.data.{FullPackage, PackageName, UnsubscribeCode}
import com.changlinli.releaseNotification.ids.AnityaId
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
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

object WebServer extends CustomLogging {

  final case class EmailAddress(str: String)

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
          .values.get("emailAddress")
          .flatMap(_.headOption)
          .toRight("Email address key not found!")
      } yield SubscribeToPackages(EmailAddress(emailAddress), packages.map(AnityaId.apply))
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

  type ErrorTIO[A] = EitherT[IO, Error, A]

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
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[Either[Error, NonEmptyList[RawAnityaProjectResultPage]]] = {
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
          val sleep =  EitherT.liftF[IO, AnityaProjectJsonWasInUnexpectedFormat, Unit](IO(logger.info("WJREIOAJWOEIRJAWRJ SLEEPING!")) *> IO.sleep(FiniteDuration(1, TimeUnit.SECONDS)))
          webRequest <* sleep
      }{ pages =>
        val currentPage = pages.head
        val numOfRemainingItems = currentPage.total_items - currentPage.items_per_page * currentPage.page
        println(s"WJEROAWJEPORJAWOPEJRPOAJOWEPRJAPOWEPROWARWEP This is our numOfRemainingItems: $numOfRemainingItems")
        numOfRemainingItems > 0
      }
    } yield allPages
    result.value
  }

  def requestAllAnityaProjects(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[Either[Error, List[RawAnityaProject]]] = {
    requestAllAnityaProjectResultPages(client, anityaPackageEndpoint, itemsPerPage)
      .|>(EitherT.apply)
      .map(pages => pages.toList.flatMap(page => page.items))
      .value
  }

  sealed trait Error extends Exception with Product with Serializable {
    val humanReadableMessage: String

    override def getMessage: String = humanReadableMessage
  }
  final case class NoPackagesFoundForAnityaIds(anityaIds: NonEmptyList[AnityaId]) extends Error {
    override val humanReadableMessage: String =
      s"We were unable to find any packages corresponding to the following anitya IDs: ${anityaIds.map(_.toInt.toString).mkString(",")}"
  }
  final case class AnityaProjectJsonWasInUnexpectedFormat(json: Json, error: DecodingFailure) extends Error {
    override val humanReadableMessage: String =
      s"The JSON passed ($json) in failed to decode properly. We saw the following error: ${error.message}"
  }

  def getFullPackagesFromSubscribeToPackages(
    email: EmailAddress,
    anityaIds: NonEmptyList[AnityaId]
  ): ConnectionIO[Ior[NoPackagesFoundForAnityaIds, NonEmptyList[FullPackage]]] = {
    Persistence.retrievePackages(anityaIds)
      .map{
        anityaIdToFullPackage =>
          val anityaIdToFullPackageOpt = anityaIds
            .map(anityaId => anityaId -> anityaIdToFullPackage.get(anityaId))
          val firstElem = anityaIdToFullPackageOpt.head match {
            case (_, Some(fullPackage)) =>
              Ior.Right(NonEmptyList.of(fullPackage))
            case (anityaId, None) =>
              Ior.Left(NoPackagesFoundForAnityaIds(NonEmptyList.of(anityaId)))
          }
          anityaIdToFullPackageOpt.tail.foldLeft(firstElem){
            case (Ior.Both(noPackagesFound, subscribeToPackagesFullName), (_, Some(fullPackage))) =>
              Ior.Both(noPackagesFound, fullPackage :: subscribeToPackagesFullName)
            case (Ior.Both(noPackagesFound, subscribeToPackagesFullName), (anityaId, None)) =>
              Ior.Both(noPackagesFound.copy(anityaIds = anityaId :: noPackagesFound.anityaIds), subscribeToPackagesFullName)
            case (Ior.Right(subscribeToPackagesFullName), (_, Some(fullPackage))) =>
              Ior.Right(fullPackage :: subscribeToPackagesFullName)
            case (Ior.Right(subscribeToPackagesFullName), (anityaId, None)) =>
              Ior.Both(NoPackagesFoundForAnityaIds(NonEmptyList.of(anityaId)), subscribeToPackagesFullName)
            case (Ior.Left(noPackagesFound), (_, Some(fullPackage))) =>
              Ior.Both(noPackagesFound, NonEmptyList.of(fullPackage))
            case (Ior.Left(noPackagesFound), (anityaId, None)) =>
              Ior.Left(noPackagesFound.copy(anityaIds = anityaId :: noPackagesFound.anityaIds))
          }
      }
  }

  def zipAnityaIdsWithUnsubscribeCodes(anityaIds: NonEmptyList[AnityaId]): IO[NonEmptyList[(AnityaId, UnsubscribeCode)]] = {
    anityaIds.traverse(anityaId => UnsubscribeCode.generateUnsubscribeCode.map((anityaId, _)))
  }

  def processDatabaseResponse(
    email: EmailAddress,
    resultOfSubscribe: Ior[NoPackagesFoundForAnityaIds, NonEmptyList[(FullPackage, UnsubscribeCode)]],
    emailSender: Email,
    hostAddress: Host,
    hostPort: Int
  ): IO[Response[IO]] = {
    resultOfSubscribe match {
      case Ior.Both(err @ NoPackagesFoundForAnityaIds(anityaIds), pkgsWithUnsubscribeCodes) =>
        val emailAction = emailSuccessfullySubscribedPackages(
          emailSender,
          email,
          pkgsWithUnsubscribeCodes,
          hostAddress,
          hostPort
        )
        emailAction.>>(Ok(s"Successfully submitted form! (with some errors: $err)"))
      case Ior.Left(err) =>
        logger.info(s"User sent in request that failed", err)
        BadRequest(s"You failed! ${err.humanReadableMessage}")
      case Ior.Right(pkgsWithUnsubscribeCodes) =>
        emailSuccessfullySubscribedPackages(
          emailSender,
          email,
          pkgsWithUnsubscribeCodes,
          hostAddress,
          hostPort
        ) >> Ok("Successfully submitted form!")
    }
  }

  def fullySubscribeToPackages(
    email: EmailAddress,
    anityaIdsWithUnsubscribeCodes: NonEmptyList[(AnityaId, UnsubscribeCode)]
  ): ConnectionIO[Ior[NoPackagesFoundForAnityaIds, NonEmptyList[(FullPackage, UnsubscribeCode)]]] = {
    val unsubscribeCodes = anityaIdsWithUnsubscribeCodes.map(_._2)
    val getFullPackages = getFullPackagesFromSubscribeToPackages(
      email,
      anityaIdsWithUnsubscribeCodes.map(_._1)
    )
    implicit val semigroupLeft: Semigroup[NoPackagesFoundForAnityaIds] =
      Semigroup.instance[NoPackagesFoundForAnityaIds]{(a, _) => a}
    IorT(getFullPackages)
      .map(fullPackages => fullPackages.zipWith(unsubscribeCodes)((_, _)))
      .flatMap{
        pkgsWithCode =>
          IorT.right[NoPackagesFoundForAnityaIds](Persistence.subscribeToPackagesFullName(email, pkgsWithCode))
            .as(pkgsWithCode)
      }
      .value
  }

  def webActionToPersistenceAction(webAction: WebAction): ConnectionIO[Ior[Error, PersistenceAction]] =
    webAction match {
      case subscribeToPackages: SubscribeToPackages =>
        Persistence.retrievePackages(subscribeToPackages.pkgs)
          .map{
            anityaIdToFullPackage =>
              val anityaIdToFullPackageOpt = subscribeToPackages
                .pkgs
                .map(anityaId => anityaId -> anityaIdToFullPackage.get(anityaId))
              val firstElem = anityaIdToFullPackageOpt.head match {
                case (_, Some(fullPackage)) =>
                  Ior.Right(SubscribeToPackagesFullName(subscribeToPackages.email, NonEmptyList.of(fullPackage)))
                case (anityaId, None) =>
                  Ior.Left(NoPackagesFoundForAnityaIds(NonEmptyList.of(anityaId)))
              }
              anityaIdToFullPackageOpt.tail.foldLeft(firstElem){
                case (Ior.Both(noPackagesFound, subscribeToPackagesFullName), (_, Some(fullPackage))) =>
                  Ior.Both(noPackagesFound, subscribeToPackagesFullName.copy(pkgs = fullPackage :: subscribeToPackagesFullName.pkgs))
                case (Ior.Both(noPackagesFound, subscribeToPackagesFullName), (anityaId, None)) =>
                  Ior.Both(noPackagesFound.copy(anityaIds = anityaId :: noPackagesFound.anityaIds), subscribeToPackagesFullName)
                case (Ior.Right(subscribeToPackagesFullName), (_, Some(fullPackage))) =>
                  Ior.Right(subscribeToPackagesFullName.copy(pkgs = fullPackage :: subscribeToPackagesFullName.pkgs))
                case (Ior.Right(subscribeToPackagesFullName), (anityaId, None)) =>
                  Ior.Both(NoPackagesFoundForAnityaIds(NonEmptyList.of(anityaId)), subscribeToPackagesFullName)
                case (Ior.Left(noPackagesFound), (_, Some(fullPackage))) =>
                  Ior.Both(noPackagesFound, SubscribeToPackagesFullName(subscribeToPackages.email, NonEmptyList.of(fullPackage)))
                case (Ior.Left(noPackagesFound), (anityaId, None)) =>
                  Ior.Left(noPackagesFound.copy(anityaIds = anityaId :: noPackagesFound.anityaIds))
              }
          }
    }

  def processInboundWebhook[F[_] : Effect](request: Request[F]): F[EmailAction] = {
    logger.info(s"Processing incoming email: $request")
    request.as[String].flatMap{
      body => Effect[F].delay(s"Body: $body")
    }
    Effect[F].delay(UnsubscribeEmailFromAllPackages(EmailAddress("hello")))
  }


  // If you see a warning here about unreachable code see https://github.com/scala/bug/issues/11457
  def webService(
    emailSender: Email,
    blocker: Blocker,
    transactor: Transactor[IO],
    hostAddress: Host,
    hostPort: Int
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
        _ <- infoIO(s"We got this form: $form")
        response <- SubscribeToPackages.fromUrlForm(form) match {
          case Left(errMsg) =>
            BadRequest(errMsg)
          case Right(incomingSubscription) =>
            for {
              _ <- infoIO(s"Persisting the following subscription: $incomingSubscription")
              packagesWithAnityaIds <- zipAnityaIdsWithUnsubscribeCodes(incomingSubscription.pkgs)
              resultOfSubscription <- fullySubscribeToPackages(incomingSubscription.email, packagesWithAnityaIds).transact(transactor)
              response <- processDatabaseResponse(incomingSubscription.email, resultOfSubscription, emailSender, hostAddress, hostPort)
            } yield response
        }
      } yield response
    case request @ GET -> Root / "incomingEmail" =>
      Ok(s"Yep this email hook is responding to GET requests!")
    case request @ POST -> Root / "incomingEmail" =>
      for {
        action <- processInboundWebhook(request)
        persistenceAction = emailActionToPersistenceAction(action)
        _ <- Persistence.processAction(persistenceAction, transactor)
        response <- Ok(s"Processed inbound email with hook: $request!")
      } yield response
    case GET -> Root / subscribePathComponent / code if subscribePathComponent == unsubscribePath =>
      Persistence
        .unsubscribeUsingCode(UnsubscribeCode.unsafeFromString(code))
        .transact(transactor)
        .flatMap(numOfPackagesUnsubscribed => Ok(s"Unsubscribed from this many packages: ${numOfPackagesUnsubscribed.toString}"))
    case GET -> Root / "search" :? SearchQueryMatcher(nameFragment) =>
      Persistence
        .searchForPackagesByNameFragment(nameFragment)
        .transact(transactor)
        .flatMap(packages => Ok(packages.asJson))
  }

  val unsubscribePath: String = "unsubscribe"

  object SearchQueryMatcher extends QueryParamDecoderMatcher[String]("name")

  private def printPackage(
    pkg: FullPackage,
    unsubscribeCode: UnsubscribeCode,
    hostAddress: Host,
    hostPort: Int
  ): String = {
    val releaseMonitoringLink = Uri(
      scheme = Some(Scheme.https),
      authority = Some(Authority(host = RegName("release-monitoring.org"))),
      path = s"/project/${pkg.anityaId}/"
    )
    s"""
       |Package Name: ${pkg.name.str}
       |Package Homepage: ${pkg.homepage}
       |release-monitoring.org link (see the bottom of this email): ${releaseMonitoringLink.renderString}
       |Unsubscribe link (see the bottom of this email): ${unsubscribeCode.formUnsubscribeUri(hostAddress, hostPort).renderString}
     """.stripMargin
  }

  private def emailSuccessfullySubscribedPackages(
    emailSender: Email,
    emailAddress: EmailAddress,
    packages: NonEmptyList[(FullPackage, UnsubscribeCode)],
    hostAddress: Host,
    hostPort: Int
  ): IO[Unit] = {
    val content =
      s"""
         |Hi you've signed up for email notifications about new packages!
         |
         |You will get an email any time one of the following packages gets a new version:
         |
         |${packages.map{case (p, unsubscribeCode) => printPackage(p, unsubscribeCode, hostAddress, hostPort)}.mkString("\n\n")}
         |
         |If you'd like to unsubscribe to updates for a particular package, simply entering any of those unsubscribe links into your web browser will do the trick.
         |
         |I currently haven't implemented the ability to unsubscribe from all packages at once. If you'd like to do so, please just send an email to admin@${hostAddress.renderString}
         |
         |The Anitya ID is used to tie a package to its representation in the Fedora backend system that powers this service. Because multiple package names
       """.stripMargin
    emailSender.email(
      to = emailAddress,
      subject = s"Signed up for notifications about ${packages.map(_._1.name.str).mkString(",")}",
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
    emailSender: Email,
    port: Int,
    hostAddress: Host,
    blocker: Blocker,
    transactor: Transactor[IO]
  )(implicit timer: Timer[IO],
    contextShift: ContextShift[IO]
  ): IO[Unit] =
    BlazeServerBuilder[IO]
      .bindHttp(port, hostAddress.renderString)
      .withHttpApp(webService(emailSender, blocker, transactor, hostAddress, port).orNotFound)
      .serve
      .compile
      .drain


}
