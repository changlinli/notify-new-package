package com.changlinli.releaseNotification

import cats.effect.{Effect, IO}
import cats.implicits._
import org.http4s.Uri
import org.http4s.syntax.all._
import scopt.OptionParser

sealed trait DatabaseCreationOption
case object CreateFromScratch extends DatabaseCreationOption
case object PreexistingDatabase extends DatabaseCreationOption

sealed trait PackageDatabaseOption
case object RecreatePackageDatabaseFromBulkDownload extends PackageDatabaseOption
case object DoNotBulkDownloadPackageDatabase extends PackageDatabaseOption

final case class ServiceConfiguration(
  databaseFile: String = "sample.db",
  portNumber: Int = 8080,
  databaseCreationOpt: DatabaseCreationOption = PreexistingDatabase,
  bindAddress: String = "127.0.0.1",
  anityaUrl: Uri = uri"https://release-monitoring.org",
  rebuildPackageDatabase: PackageDatabaseOption = DoNotBulkDownloadPackageDatabase
)

object ServiceConfiguration {
  val cmdLineOptionParser: OptionParser[ServiceConfiguration] = new scopt.OptionParser[ServiceConfiguration]("notify-new-package") {
    head("notify-new-package", "0.0.1")

    opt[String]('f', "filename").action{
      (filenameStr, config) => config.copy(databaseFile = filenameStr)
    }

    opt[Int]('p', "port").action{
      (port, config) => config.copy(portNumber = port)
    }

    opt[Unit]('i', "initialize-database").action{
      (_, config) => config.copy(databaseCreationOpt = CreateFromScratch)
    }

    opt[String]('a', "bind-address").action{
      (address, config) => config.copy(bindAddress = address)
    }

    opt[String]('u', name="anitya-website-url")
      .validate{
        url => Uri.fromString(url) match {
          case Left(err) =>
            failure(s"The argument passed as a URL ($url) does not seem to be a valid URL due to the following reason: ${err.message}")
          case Right(_) =>
            success
        }
      }
      .action{
        (url, config) =>
          // We can use unsafeFromString because we already checked fromString
          // previously. Yes Scopt is annoying.
          config.copy(anityaUrl = Uri.unsafeFromString(url))
      }

    help("help")

    version("version")
  }

  def parseCommandLineOptions(args: List[String]): IO[ServiceConfiguration] = {
    cmdLineOptionParser.parse(args, ServiceConfiguration()) match {
      case Some(configuration) => configuration.pure[IO]
      case None => Effect[IO].raiseError[ServiceConfiguration](new Exception("Bad command line options"))
    }
  }
}
