package com.changlinli.releaseNotification

import java.util.concurrent.TimeUnit

import cats.data.{EitherT, NonEmptyList}
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import com.changlinli.releaseNotification.errors.AnityaProjectJsonWasInUnexpectedFormat
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Decoder
import org.http4s.{Method, Request, Uri}
import org.http4s.client.Client

import scala.concurrent.duration.FiniteDuration

object PackageDownloader extends CustomLogging {
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
    clientRequest
      .flatTap(str => IO(logger.debug(s"This is what came out of our request! $str")))
      .attempt
      .flatMap{
        strOrErr =>
          logger.debug(s"This is the raw string: $strOrErr")
          strOrErr
            .fold[IO[String]](IO.raiseError, x => IO(x))
            .map(io.circe.parser.parse)
            .flatMap(_.fold(IO.raiseError, x => IO(x)))
      }
      .attempt
      .flatMap{
        case Right(json) =>
          logger.info(s"Received ${json.noSpaces.length} characters of JSON")
          logger.debug(s"Received this JSON from the Anitya web server about packages: $json")
          val decodeResult = Decoder[RawAnityaProjectResultPage].decodeJson(json).leftMap(AnityaProjectJsonWasInUnexpectedFormat(json, _))
          IO(logger.debug(s"THIS WAS THE RESULT AFTER DECODING: $decodeResult")) *>
            IO(decodeResult)
        case Left(err) =>
          logger.error("We blew up!", err)
          IO.raiseError(err)
      }
  }

  type UnexpectedFormatTIO[A] = EitherT[IO, AnityaProjectJsonWasInUnexpectedFormat, A]

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

  private def processSinglePage(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int,
    transactor: Transactor[IO],
    pageNumber: Int
  )(implicit timer: Timer[IO], contextShift: ContextShift[IO]): IO[Either[AnityaProjectJsonWasInUnexpectedFormat, RawAnityaProjectResultPage]] = {
      requestProjectByPage(client, anityaPackageEndpoint, itemsPerPage, pageNumber)
        .flatMap{
          _.traverse{
            pageOfResults =>
              logger.info(s"About to persist ${pageOfResults.items.length} Anitya packages")
              logger.debug(s"About to persist this: $pageOfResults")
              pageOfResults
                .items
                .traverse(Persistence.persistRawAnityaProject)
                .transact(transactor)
                .as(pageOfResults)
          }
        }
  }

  def processAllAnityaProjects(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int,
    transactor: Transactor[IO]
  )(implicit timer: Timer[IO], contextShift: ContextShift[IO]): IO[Unit] = {
    fs2.Stream.unfoldEval(1){ page =>
      logger.info(s"Processing page number: $page")
      val processSingleProjectPage = processSinglePage(client, anityaPackageEndpoint, itemsPerPage, transactor, page)
      processSingleProjectPage.map{
        case Right(resultPage) =>
          if (resultPage.items_per_page * page < resultPage.total_items) {
            Some(((), page + 1))
          } else {
            None
          }
        case Left(err) =>
          logger.error(s"We blew up when doing processing an Anitya page!", err)
          Some((), page)
      }
    }
      .map(Right.apply)
      .interleave(fs2.Stream.sleep(FiniteDuration(1, TimeUnit.SECONDS)).repeat.map(Left.apply))
      .collect{case Right(x) => x}
      .compile
      .drain
  }

}
