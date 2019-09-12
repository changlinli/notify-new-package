package com.changlinli.releaseNotification

import java.util.UUID

import cats.effect.{Effect, IO}
import cats.implicits._
import com.changlinli.releaseNotification.data.EmailAddress
import dev.profunktor.fs2rabbit.model.QueueName
import org.http4s.Uri
import org.http4s.Uri.{Authority, Host, Ipv4Address, Ipv6Address, RegName}
import org.http4s.syntax.all._
import org.http4s.util.CaseInsensitiveString
import scopt.OptionParser

import scala.util.Try

sealed trait DatabaseCreationOption
case object CreateFromScratch extends DatabaseCreationOption
case object PreexistingDatabase extends DatabaseCreationOption

sealed trait PackageDatabaseOption
case object RecreatePackageDatabaseFromBulkDownload extends PackageDatabaseOption
case object DoNotBulkDownloadPackageDatabase extends PackageDatabaseOption

sealed trait LogLevel
case object Quiet extends LogLevel
case object Normal extends LogLevel
case object Verbose extends LogLevel
case object VeryVerbose extends LogLevel

object LogLevel {
  def fromInt(int: Int): LogLevel = {
    if (int <= 0) {
      Quiet
    } else if (int == 1) {
      Normal
    } else if (int == 2) {
      Verbose
    } else {
      VeryVerbose
    }
  }

  def toSLF4JString(logLevel: LogLevel): String = logLevel match {
    case Quiet => "WARN"
    case Normal => "INFO"
    case Verbose => "DEBUG"
    case VeryVerbose => "TRACE"
  }
}

final case class ServiceConfiguration(
  databaseFile: String = "sample.db",
  bindPortNumber: Int = 8080,
  databaseCreationOpt: DatabaseCreationOption = PreexistingDatabase,
  bindAddress: Host = Ipv4Address.unsafeFromString("127.0.0.1"),
  urlOfSite: Authority = Authority(host = RegName("example.com")),
  anityaUrl: Uri = uri"https://release-monitoring.org",
  rebuildPackageDatabase: PackageDatabaseOption = DoNotBulkDownloadPackageDatabase,
  adminEmailRedirect: EmailAddress = EmailAddress.unsafeFromString("example@example.com"),
  sendGridAPIKey: String = "unknownApiKey",
  rabbitMQQueueName: QueueName = QueueName("00000000-0000-0000-0000-000000000000"),
  logLevel: LogLevel = Normal
)

object ServiceConfiguration {
  private def parseHostFromString(address: String): Host = {
    Ipv4Address.fromString(address)
      .orElse(Ipv6Address.fromString(address))
      .getOrElse(RegName(CaseInsensitiveString(address)))
  }
  val cmdLineOptionParser: OptionParser[ServiceConfiguration] = new scopt.OptionParser[ServiceConfiguration]("notify-new-package") {
    head("notify-new-package", "0.0.1")

    opt[Int]('l', "log-level")
      .action{(logLevelInt, config) => config.copy(logLevel = LogLevel.fromInt(logLevelInt))}
      .text("The log level you wish this service to run at. 0 and below is quiet, 1 is normal, 2 is verbose, and 3 and above is very verbose.")

    opt[String]('q', "rabbitmq-queue-name")
      .required()
      .validate{
        str =>
          Try(UUID.fromString(str))
            .fold(
              _ => failure(s"$str was not a valid UUID string!"),
              _ => success
            )
      }
      .action{(queueNameStr, config) => config.copy(rabbitMQQueueName = QueueName(queueNameStr))}
      .text("To interface with Fedora's infrastructure, according to its documentation" +
        ", this should be a UUID.")

    opt[String]('s', "sendgrid-api-key")
      .required()
      .action{
        (sendGridAPIKey, config) => config.copy(sendGridAPIKey = sendGridAPIKey)
      }

    opt[String]('p', "public-site-name").required()
      .validate{
        publicSiteName =>
          publicSiteName.split(":").toList.get(1) match {
            case Some(potentialPort) =>
              if (potentialPort.toIntOption.isDefined) {
                success
              } else {
                failure(s"The port number passed (the string after the colon) was $potentialPort, which does not seem to be a valid integer")
              }
            case None =>
              success
          }
      }
      .action{
        (publicSiteName, config) =>
          val publicAuthority = publicSiteName.split(":").toList match {
            case domain :: Nil =>
              Authority(host = parseHostFromString(domain))
            case domain :: portStr :: _ =>
              val host = parseHostFromString(domain)
              // We can use an unsafe toInt because we previously validated in
              // validate that this is a valid int... yes scopt sucks with its design
              val port = portStr.toInt
              Authority(host = host, port = Some(port))
            case Nil =>
              // This is impossible because of the previous validation step that has a .get(1)
              throw new Exception("Programmer error! This should be impossible because of a previous validation step")
          }
          config.copy(urlOfSite = publicAuthority)
      }

    opt[Unit]('b', "build-package-database")
      .action{
        (_, config) => config.copy(rebuildPackageDatabase = RecreatePackageDatabaseFromBulkDownload)
      }
      .text("Setting this flag causes the program to redownload the entire package database from the configured Anitya (release-monitoring) website.")

    opt[String]('e', "admin-email-address").required()
      .validate{
        adminEmailAddress => EmailAddress.fromString(adminEmailAddress) match {
          case None =>
            failure(s"The argument passed as an email address ($adminEmailAddress) does not seem to be a valid email address.")
          case Some(_) =>
            success
        }
      }
      .action{
        (adminEmailAddress, config) =>
          // We can use unsafeFromString because we already checked fromString
          // previously. Yes Scopt is annoying.
          config.copy(adminEmailRedirect = EmailAddress.unsafeFromString(adminEmailAddress))
      }

    opt[String]('f', "filename").action{
      (filenameStr, config) => config.copy(databaseFile = filenameStr)
    }

    opt[Int]('p', "port").action{
      (port, config) => config.copy(bindPortNumber = port)
    }

    opt[Unit]('i', "initialize-database").action{
      (_, config) => config.copy(databaseCreationOpt = CreateFromScratch)
    }

    opt[String]('a', "bind-address")
      .action{
        (address, config) =>
          val host = parseHostFromString(address)
          config.copy(bindAddress = host)
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
