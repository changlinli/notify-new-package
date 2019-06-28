package com.changlinli.releaseNotification

import java.io.{FileInputStream, InputStreamReader}
import java.security.{KeyStore, SecureRandom}

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{ConcurrentEffect, ExitCode, IO, IOApp}
import cats.implicits._
import dev.profunktor.fs2rabbit.Blah
import dev.profunktor.fs2rabbit.config.declaration.{AutoDelete, DeclarationQueueConfig, NonDurable, NonExclusive}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.effects.EnvelopeDecoder
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, ExchangeName, QueueName, RoutingKey}
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor, Json}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

import scala.language.higherKinds

object Main extends IOApp {
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
//    sslContext <- createClientSSLContextFromRawStrings(
//      caCertificateFilename = "cacert.pem",
//      clientPrivateKeyFilename = "fedora-key.pem",
//      serverPublicKeyFilename = "fedora-cert.pem"
//    )
    sslContext <- createSSLContext
    fs2Rabbit <- Blah.customCreateFs2Rabbit[IO](
      config = config,
      sslContext = Some(sslContext)
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
      println(s"Throwing away payload because routing key was ${routingKeySeen}")
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

  override def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE"))
    emailSender <- Email.initialize
   fs2Rabbit <- generateFs2Rabbit
   _ <- fs2Rabbit.createConnectionChannel.use { implicit channel =>
     for {
       _ <- IO(println("Are we here?"))
//       _ <- fs2Rabbit.declareExchange(exchangeName, ExchangeType.Topic)
       _ <- fs2Rabbit.declareQueue(DeclarationQueueConfig(queueName = queueName, durable = NonDurable, exclusive = NonExclusive, autoDelete = AutoDelete, arguments = Map.empty))
       _ <- fs2Rabbit.bindQueue(queueName, exchangeName, routingKey)
       consumer <- fs2Rabbit.createAutoAckConsumer[Json](
         queueName = queueName
       )
       _ <- consumer
         .map(parseEnvelope)
         .evalTap(x => IO(println(x)))
         .evalMap{
           case Some(value) =>
             emailSender.email(
               to = "mail@changlinli.com",
               from = "auto@example.com",
               subject = "Something updated!",
               content = value.printEmailTitle
             )
           case None => IO.unit
         }
         .compile
         .drain
     } yield ()
   }
    //_ <- fs2.Stream
      //.resource(Blocker[IO])
      //.flatMap{ blocker =>
        //fs2.io.file
          //.readAll[IO](Paths.get("/home/changlin/scratch/fedora-messaging/messaging-pipe"), blocker, 4096)
          //.through(text.utf8Decode).groupAdjacentBy()
      //}
      //.compile
      //.drain
  } yield ExitCode.Success
}
