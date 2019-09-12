package com.changlinli.releaseNotification

import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.TimeUnit

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{ConcurrentEffect, ExitCode, IO, Timer}
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

import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

object Main extends MyIOApp with Logging {

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

  val exchangeName = ExchangeName("amq.topic")

  val routingKey = RoutingKey("#")


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
              logger.info(s"About to persist ${pageOfResults.items.length} Anitya packages")
              logger.debug(s"About to persist this: $pageOfResults")
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
    cmdLineOpts <- ServiceConfiguration.parseCommandLineOptions(args)
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, LogLevel.toSLF4JString(cmdLineOpts.logLevel)))
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.SHOW_DATE_TIME_KEY, "true"))
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
    _ <- IO(logger.info(s"These are the commandline options we parsed: $cmdLineOpts"))
    emailSender <- EmailSender.initialize(cmdLineOpts.urlOfSite.host, cmdLineOpts.sendGridAPIKey)
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
            queueName = cmdLineOpts.rabbitMQQueueName,
            durable = Durable,
            exclusive = NonExclusive,
            autoDelete = NonAutoDelete,
            arguments = Map.empty
          )
        )
        _ <- fs2Rabbit.bindQueue(cmdLineOpts.rabbitMQQueueName, exchangeName, routingKey)
        consumer <- fs2Rabbit.createAutoAckConsumer[Json](
          queueName = cmdLineOpts.rabbitMQQueueName
        )
        _ <- RabbitMQListener.consumeRabbitMQ(consumer, doobieTransactor, emailSender)
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
        webServerFiber <- WebServer.runWebServer(
          emailSender,
          cmdLineOpts.urlOfSite,
          cmdLineOpts.bindPortNumber,
          cmdLineOpts.bindAddress,
          blocker,
          doobieTransactor,
          cmdLineOpts.adminEmailRedirect
        ).start
        stream = fs2.io.stdin[IO](1, blocker).evalMap{
          b =>
            if (b == 's'.toByte) {
              IO(System.exit(0))
            } else {
              IO.unit
            }
        }
        keyPressDetectorFiber <- stream.compile.drain.start
        garbageCleanupFiber <- GarbageDataCollection
          .periodicallyCleanUp(doobieTransactor)
          .compile
          .drain
          .start
        _ <- rabbitFiber.join
        _ <- anityaFiber.join
        _ <- webServerFiber.join
        _ <- keyPressDetectorFiber.join
        _ <- garbageCleanupFiber.join
      } yield ()
    }
  } yield ExitCode.Success

}
