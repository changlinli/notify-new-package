package com.changlinli.releaseNotification

import java.io.{FileInputStream, InputStreamReader}
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.Executors

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{Blocker, ConcurrentEffect, Effect, ExitCode, IO, IOApp}
import cats.implicits._
import com.rabbitmq.client.DefaultSaslConfig
import dev.profunktor.fs2rabbit.config.declaration.{AutoDelete, DeclarationQueueConfig, NonDurable, NonExclusive}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.effects.EnvelopeDecoder
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, ExchangeName, QueueName, RoutingKey}
import doobie._
import doobie.implicits._
import grizzled.slf4j.Logging
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor, Json}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import scala.language.higherKinds

object Main extends IOApp with Logging {
  sealed trait DatabaseCreationOpt
  case object CreateFromScratch extends DatabaseCreationOpt
  case object PreexistingDatabase extends DatabaseCreationOpt

  final case class Config(
    databaseFile: String = "sample.db",
    portNumber: Int = 8080,
    databaseCreationOpt: DatabaseCreationOpt = PreexistingDatabase
  )

  val cmdLineOptionParser = new scopt.OptionParser[Config]("notify-new-package") {
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

    help("help")

    version("version")
  }

  private val blocker: Blocker = Blocker.liftExecutionContext(
    scala.concurrent.ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool
    )
  )

  val config = Fs2RabbitConfig(
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

  final case class IncomingSubscriptionRequest(packages: List[String], emailAddress: String)

  object IncomingSubscriptionRequest {
    def fromUrlForm(urlForm: UrlForm): Either[String, IncomingSubscriptionRequest] = {
      for {
        packages <- urlForm
          .values
          .get("packages")
          .toRight("Packages key not found!")
        emailAddress <- urlForm
          .values.get("emailAddress")
          .flatMap(_.headOption)
          .toRight("Email address key not found!")
      } yield IncomingSubscriptionRequest(packages.toList, emailAddress)
    }
  }

  // If you see a warning here about unreachable code see https://github.com/scala/bug/issues/11457
  val webService = HttpRoutes.of[IO]{
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
              .>>(Ok("Successfully submitted form!"))
        }
      } yield response
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

  def createClientSSLContextFromRawStrings(caCertificateFilename: String,
                                           clientPrivateKeyFilename: String,
                                           serverPublicKeyFilename: String): IO[SSLContext] = {
    IO{
      val caFileInput = new FileInputStream(caCertificateFilename)

      val certificateFactory = new CertificateFactory()

      val caCert = certificateFactory.engineGenerateCertificate(caFileInput)

      val serverPublicFileInput = new FileInputStream(serverPublicKeyFilename)
      val serverPublicCert = certificateFactory.engineGenerateCertificate(serverPublicFileInput)

      val privateKeyFileInput = new FileInputStream(clientPrivateKeyFilename)

      val privateKeyInfo = new PEMParser(new InputStreamReader(privateKeyFileInput)).readObject().asInstanceOf[PrivateKeyInfo]
      val converter = new JcaPEMKeyConverter()
      val privateKey = converter.getPrivateKey(privateKeyInfo)

      val keyStore = KeyStore.getInstance("JKS")
      keyStore.load(null)
      keyStore.setCertificateEntry("fedora-custom-ca", caCert)
      keyStore.setKeyEntry("private-key", privateKey, "changeit".toCharArray, Array(caCert, serverPublicCert))
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, "changeit".toCharArray)
      val tmf = TrustManagerFactory.getInstance("SunX509")
      tmf.init(keyStore)

      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom())
      sslContext
    }
  }

  val generateFs2Rabbit: IO[Fs2Rabbit[IO]] = for {
    sslContext <- createSSLContext
    fs2Rabbit <- Fs2Rabbit[IO](
      config = config,
      sslContext = Some(sslContext),
      saslConfig = DefaultSaslConfig.EXTERNAL
    )
  } yield fs2Rabbit

  val queueName = QueueName("00000000-0000-0000-0000-000000000000")

  val exchangeName = ExchangeName("amq.topic")

  val routingKey = RoutingKey("#")

  final case class DependencyUpdate(packageName: String, packageVersion: String, previousVersion: String, homepage: String) {
    def printEmailTitle: String = s"Package $packageName was just upgraded from " +
      s"version $previousVersion to $packageVersion"

    def printEmailBody: String = s"Package $packageName was just upgraded from " +
      s"version $previousVersion to $packageVersion. Check out its homepage " +
      s"$homepage for more details."
  }

  val anityaRoutingKey = RoutingKey("org.release-monitoring.prod.anitya.project.version.update")

  def parseEnvelope(envelope: AmqpEnvelope[Json]): Option[DependencyUpdate] = {
    val routingKeySeen = envelope.routingKey
    if (anityaRoutingKey == routingKeySeen) {
      parsePayload(envelope.payload)
    } else {
      logger.info(s"Throwing away payload because routing key was ${routingKeySeen.value}")
      None
    }
  }

  implicit val dependencyUpdateDecoder: Decoder[DependencyUpdate] = new Decoder[DependencyUpdate] {
    override def apply(c: HCursor): Result[DependencyUpdate] = {
      for {
        projectName <- c.downField("project").downField("name").as[String]
        projectVersion <- c.downField("project").downField("version").as[String]
        previousVersion <- c.downField("project").downField("old_version").as[String]
        homepage <- c.downField("project").downField("homepage").as[String]
      } yield DependencyUpdate(
        packageName = projectName,
        packageVersion = projectVersion,
        previousVersion = previousVersion,
        homepage = homepage
      )
    }
  }

  def parsePayload(payload: Json): Option[DependencyUpdate] = {
    payload.as[DependencyUpdate].toOption
  }

  def persistSubscriptions(incomingSubscriptionRequest: IncomingSubscriptionRequest): doobie.ConnectionIO[List[Int]] =
    incomingSubscriptionRequest
      .packages
      .traverse(pkg => Persistence.insertIntoDB(name = incomingSubscriptionRequest.emailAddress, packageName = pkg))

  val runWebServer: IO[Unit] = BlazeServerBuilder[IO]
    .bindHttp(8080, "localhost")
    .withHttpApp(webService.orNotFound)
    .serve
    .compile
    .drain

  override def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE"))
    cmdLineOpts <- cmdLineOptionParser.parse(args, Config()) match {
      case Some(configuration) => configuration.pure[IO]
      case None => Effect[IO].raiseError[Config](new Exception("Bad command line options"))
    }
    transactor <- cmdLineOpts.databaseCreationOpt match {
      case CreateFromScratch => Persistence.initializeDatabaseFromScratch(cmdLineOpts.databaseFile)
      case PreexistingDatabase => Persistence.transactorA(cmdLineOpts.databaseFile).pure[IO]
    }
    _ <- Persistence.insertIntoDB("mail@changlinli.com", "ALL").transact(transactor)
    emailSender <- Email.initialize
    fs2Rabbit <- generateFs2Rabbit
    _ <- fs2Rabbit.createConnectionChannel.use { implicit channel =>
      for {
        _ <- fs2Rabbit.declareQueue(
          DeclarationQueueConfig(
            queueName = queueName,
            durable = NonDurable,
            exclusive = NonExclusive,
            autoDelete = AutoDelete,
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
            case Some(value) =>
              for {
                emailAddresses <- Persistence.retrieveAllEmailsWithPackageName(value.packageName)
                _ <- IO(s"All email addresses subscribed to ${value.packageName}: $emailAddresses")
                emailAddressesSubscribedToAllUpdates <- Persistence.retrieveAllEmailsSubscribedToAll
                _ <- IO(s"All email addresses subscribed to ALL: $emailAddressesSubscribedToAllUpdates")
                _ <- (emailAddressesSubscribedToAllUpdates ++ emailAddresses).traverse{ emailAddress =>
                  logger.info(s"Emailing out an update of ${value.packageName} to $emailAddress")
                  emailSender.email(
                    to = emailAddress,
                    from = "auto@example.com",
                    subject = s"${value.packageName} was updated!",
                    content = value.printEmailTitle
                  )
                }
              } yield ()
            case None => IO.unit
          }
          .compile
          .drain
      } yield ()
    }.start
    _ <- runWebServer
  } yield ExitCode.Success
}
