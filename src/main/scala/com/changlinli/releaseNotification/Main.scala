package com.changlinli.releaseNotification

import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.TimeUnit

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{ConcurrentEffect, Effect, ExitCode, IO, Timer}
import cats.implicits._
import com.rabbitmq.client.DefaultSaslConfig
import dev.profunktor.fs2rabbit.config.declaration.{DeclarationQueueConfig, Durable, NonAutoDelete, NonExclusive}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.effects.EnvelopeDecoder
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, ExchangeName, QueueName, RoutingKey}
import doobie.implicits._
import doobie.util.transactor.Transactor
import grizzled.slf4j.Logging
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.http4s._
import org.http4s.client.{Client, JavaNetClientBuilder}
import org.http4s.implicits._
import scopt.OptionParser

import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

object Main extends MyIOApp with Logging {
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
    ipAddress: String = "127.0.0.1",
    anityaUrl: Uri = uri"https://release-monitoring.org",
    rebuildPackageDatabase: PackageDatabaseOption = DoNotBulkDownloadPackageDatabase
  )

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
      (address, config) => config.copy(ipAddress = address)
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

  val rabbitMQConfig = Fs2RabbitConfig(
    nodes = NonEmptyList.of(Fs2RabbitNodeConfig(host = "rabbitmq.fedoraproject.org", 5671)),
    virtualHost = "/public_pubsub",
    connectionTimeout = 0,
    ssl = true,
    username = Some("fedora"),
    password = None,
    requeueOnNack = true,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  val readKeyStore: IO[KeyStore] = {
    IO{
      val keyStoreStream = new FileInputStream("truststore.pfx")
      val keyStore = KeyStore.getInstance("PKCS12")
      keyStore.load(keyStoreStream, "PASSWORD".toCharArray)
      keyStore
    }
  }

  implicit def myJsonDecoder[F[_] : ConcurrentEffect]: EnvelopeDecoder[F, Json] = Kleisli[F, AmqpEnvelope[Array[Byte]], Json]{
    envelope: AmqpEnvelope[Array[Byte]] =>
      AmqpEnvelope.stringDecoder[F].run.apply(envelope).flatMap{
        str => io.circe.parser.parse(str) match {
          case Right(result) => result.pure[F]
          case Left(err) => ConcurrentEffect[F].raiseError(err)
        }
      }
  }

  val createSSLContext: IO[SSLContext] = {
    readKeyStore.flatMap{keyStore => IO{
      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, "PASSWORD".toCharArray)
      val sslContext = SSLContext.getInstance("TLS")
      val tmf = TrustManagerFactory.getInstance("SunX509")
      tmf.init(keyStore)
      sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom())
      sslContext
    }}
  }

  val generateFs2Rabbit: IO[Fs2Rabbit[IO]] = for {
    sslContext <- createSSLContext
    fs2Rabbit <- Fs2Rabbit[IO](
      config = rabbitMQConfig,
      sslContext = Some(sslContext),
      saslConfig = DefaultSaslConfig.EXTERNAL
    )
  } yield fs2Rabbit

  val queueName = QueueName("049ab00b-c653-4112-a6e3-d921aaf90ec9")

  val exchangeName = ExchangeName("amq.topic")

  val routingKey = RoutingKey("#")

  final case class DependencyUpdate(packageName: String, packageVersion: String, previousVersion: String, homepage: String, anityaId: Int) {
    def printEmailTitle: String = s"Package $packageName was just upgraded from " +
      s"version $previousVersion to $packageVersion"

    def printEmailBody: String = s"Package $packageName was just upgraded from " +
      s"version $previousVersion to $packageVersion. Check out its homepage " +
      s"$homepage for more details."
  }

  val anityaRoutingKeys: Set[RoutingKey] = Set(
    RoutingKey("org.release-monitoring.prod.anitya.project.version.update"),
    RoutingKey("org.fedoraproject.prod.hotness.update.bug.file")
  )

  sealed trait Error extends Exception
  final case class PayloadParseFailure(decodingFailure: DecodingFailure, jsonAttempted: Json) extends Error
  final case class IncorrectRoutingKey(routingKeyObserved: RoutingKey) extends Error

  def parseEnvelope(envelope: AmqpEnvelope[Json]): Either[Error, DependencyUpdate] = {
    val routingKeySeen = envelope.routingKey
    if (anityaRoutingKeys.contains(routingKeySeen)) {
      parsePayload(envelope.payload).left.map(PayloadParseFailure(_, envelope.payload))
    } else {
      logger.info(s"Throwing away payload because routing key was ${routingKeySeen.value}")
      Left(IncorrectRoutingKey(routingKeySeen))
    }
  }

  implicit val dependencyUpdateDecoder: Decoder[DependencyUpdate] = new Decoder[DependencyUpdate] {
    override def apply(c: HCursor): Result[DependencyUpdate] = {
      for {
        projectName <- c.downField("project").downField("name").as[String]
        projectVersion <- c.downField("project").downField("version").as[String]
        previousVersion <- c.downField("message").downField("old_version").as[String]
        homepage <- c.downField("project").downField("homepage").as[String]
        anityaId <- c.downField("project").downField("id").as[Int]
      } yield DependencyUpdate(
        packageName = projectName,
        packageVersion = projectVersion,
        previousVersion = previousVersion,
        homepage = homepage,
        anityaId = anityaId
      )
    }
  }

  def parsePayload(payload: Json): Either[DecodingFailure, DependencyUpdate] = {
    payload.as[DependencyUpdate]
  }


  def processAllAnityaProjects(
    client: Client[IO],
    anityaPackageEndpoint: Uri,
    itemsPerPage: Int,
    transactor: Transactor[IO]
  )(implicit timer: Timer[IO]): IO[Unit] = {
    fs2.Stream.unfoldEval(1){ page =>
      logger.info(s"Processing page number: $page")
      val processSingleProjectPage = WebServer
        .requestProjectByPage(client, anityaPackageEndpoint, itemsPerPage, page)
        .flatMap{
          _.traverse{
            pageOfResults =>
              logger.info(s"About to persist this: $pageOfResults")
              pageOfResults
                .items
                .traverse(Persistence.persistRawAnityaProject)
                .transact(transactor)
                .as(pageOfResults)
          }
        }
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

  override def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG"))
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.SHOW_DATE_TIME_KEY, "true"))
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
    cmdLineOpts <- cmdLineOptionParser.parse(args, ServiceConfiguration()) match {
      case Some(configuration) => configuration.pure[IO]
      case None => Effect[IO].raiseError[ServiceConfiguration](new Exception("Bad command line options"))
    }
    _ <- IO(logger.info(s"These are the commandline options we parsed: $cmdLineOpts"))
    emailSender <- Email.initialize
    fs2Rabbit <- generateFs2Rabbit
    allResources = for {
      blazeClient <- JavaNetClientBuilder[IO](blocker).resource
      fs2RabbitChannel <- fs2Rabbit.createConnectionChannel
      doobieTransactor <- Persistence.createTransactor(cmdLineOpts.databaseFile)
    } yield (blazeClient, fs2RabbitChannel, doobieTransactor)
    _ <- allResources.use { case (blazeClient, fs2RabbitChannel, doobieTransactor) =>
      implicit val channel: model.AMQPChannel = fs2RabbitChannel
      val runRabbitListener = for {
        _ <- fs2Rabbit.declareQueue(
          DeclarationQueueConfig(
            queueName = queueName,
            durable = Durable,
            exclusive = NonExclusive,
            autoDelete = NonAutoDelete,
            arguments = Map.empty
          )
        )
        _ <- fs2Rabbit.bindQueue(queueName, exchangeName, routingKey)
        consumer <- fs2Rabbit.createAutoAckConsumer[Json](
          queueName = queueName
        )
        _ <- consumer
          .map(parseEnvelope)
          .evalTap(x => IO(logger.info(x)))
          .evalMap{
            case Right(value) =>
              for {
                emailAddresses <- Persistence.retrieveAllEmailsWithAnityaId(doobieTransactor, value.anityaId)
                _ <- IO(s"All email addresses subscribed to ${value.packageName}: $emailAddresses")
                emailAddressesSubscribedToAllUpdates <- Persistence.retrieveAllEmailsSubscribedToAll(doobieTransactor)
                _ <- IO(s"All email addresses subscribed to ALL: $emailAddressesSubscribedToAllUpdates")
                _ <- (emailAddressesSubscribedToAllUpdates ++ emailAddresses).traverse{ emailAddress =>
                  logger.info(s"Emailing out an update of ${value.packageName} to $emailAddress")
                  emailSender.email(
                    to = emailAddress,
                    subject = s"${value.packageName} was updated!",
                    content = value.printEmailTitle
                  )
                }
              } yield ()
            case Left(PayloadParseFailure(decodeError, json)) => IO(logger.warn(s"We saw this payload parse error!: $decodeError\n$json"))
            case Left(IncorrectRoutingKey(incorrectRoutingKey)) => IO(logger.debug(s"Ignoring this routing key... $incorrectRoutingKey"))
          }
          .compile
          .drain
      } yield ()
      val processAnityaInBackground = processAllAnityaProjects(blazeClient, cmdLineOpts.anityaUrl, 100, doobieTransactor)
      for {
        _ <- cmdLineOpts.databaseCreationOpt match {
          case PreexistingDatabase => IO.unit
          case CreateFromScratch => Persistence.initializeDatabase.transact(doobieTransactor)
        }
        rabbitFiber <- runRabbitListener.start
        anityaFiber <- cmdLineOpts.rebuildPackageDatabase match {
          case RecreatePackageDatabaseFromBulkDownload => processAnityaInBackground.start
          case DoNotBulkDownloadPackageDatabase => IO.unit.start
        }
        webServerFiber <- WebServer.runWebServer(emailSender, cmdLineOpts.portNumber, cmdLineOpts.ipAddress, blocker, doobieTransactor).start
        stream = fs2.io.stdin[IO](1, blocker).evalMap{
          b =>
            if (b == 's'.toByte) {
              IO(System.exit(0))
            } else {
              IO.unit
            }
        }
        keyPressDetectorFiber <- stream.compile.drain.start
        _ <- rabbitFiber.join
        _ <- anityaFiber.join
        _ <- webServerFiber.join
        _ <- keyPressDetectorFiber.join
      } yield ()
    }
  } yield ExitCode.Success

}
