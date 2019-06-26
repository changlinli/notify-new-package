package com.changlinli.releaseNotification

import java.io.{BufferedInputStream, FileInputStream, InputStreamReader}
import java.nio.file.Paths
import java.security.{KeyFactory, KeyStore, PrivateKey, SecureRandom}
import java.security.cert.Certificate
import java.security.spec.PKCS8EncodedKeySpec

import cats.data.NonEmptyList
import cats.effect.{Blocker, ConcurrentEffect, ExitCode, IO, IOApp}
import dev.profunktor.fs2rabbit.Blah
import dev.profunktor.fs2rabbit.config.declaration.{AutoDelete, DeclarationQueueConfig, NonDurable, NonExclusive}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.{ConnectionEffect, Fs2Rabbit}
import dev.profunktor.fs2rabbit.model.{ExchangeName, ExchangeType, QueueName, RoutingKey}
import fs2.text
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.util.io.pem.PemObjectParser

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

  override def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO(System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE"))
   fs2Rabbit <- generateFs2Rabbit
   _ <- fs2Rabbit.createConnectionChannel.use { implicit channel =>
     for {
       _ <- IO(println("Are we here?"))
//       _ <- fs2Rabbit.declareExchange(exchangeName, ExchangeType.Topic)
       _ <- fs2Rabbit.declareQueue(DeclarationQueueConfig(queueName = queueName, durable = NonDurable, exclusive = NonExclusive, autoDelete = AutoDelete, arguments = Map.empty))
       _ <- fs2Rabbit.bindQueue(queueName, exchangeName, routingKey)
       consumer <- fs2Rabbit.createAutoAckConsumer(
         queueName = queueName
       )
       _ <- consumer.map(_.toString).evalMap(x => IO(println(x))).compile.drain
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
