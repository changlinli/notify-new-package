package com.changlinli.releaseNotification

import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.changlinli.releaseNotification.Main.DependencyUpdate
import com.changlinli.releaseNotification.data.PackageVersion
import com.changlinli.releaseNotification.ids.AnityaId
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, RoutingKey}
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.{DecodingFailure, Json}

object RabbitMQListener extends CustomLogging {
  private val anityaRoutingKeys: Set[RoutingKey] = Set(
    RoutingKey("org.release-monitoring.prod.anitya.project.version.update"),
    RoutingKey("org.fedoraproject.prod.hotness.update.bug.file")
  )

  private def parseEnvelope(envelope: AmqpEnvelope[Json]): Either[AppError, DependencyUpdate] = {
    val routingKeySeen = envelope.routingKey
    if (anityaRoutingKeys.contains(routingKeySeen)) {
      envelope.payload.as[DependencyUpdate].left.map(PayloadParseFailure(_, envelope.payload))
    } else {
      logger.info(s"Throwing away payload because routing key was ${routingKeySeen.value}")
      Left(IncorrectRoutingKey(routingKeySeen))
    }
  }

  private def sendEmailAboutDependencyUpdate(
    update: DependencyUpdate,
    doobieTransactor: Transactor[IO],
    emailSender: EmailSender
  )(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    for {
      _ <- Persistence.updatePackageVersion(
        AnityaId(update.anityaId),
        PackageVersion(update.packageVersion)
      ).transact(doobieTransactor)
      emailAddresses <- Persistence.retrieveAllEmailsWithAnityaIdIO(doobieTransactor, update.anityaId)
      _ <- IO(s"All email addresses subscribed to ${update.packageName}: $emailAddresses")
      emailAddressesSubscribedToAllUpdates <- Persistence.retrieveAllEmailsSubscribedToAllFullIO(doobieTransactor)
      _ <- IO(s"All email addresses subscribed to ALL: $emailAddressesSubscribedToAllUpdates")
      _ <- (emailAddressesSubscribedToAllUpdates ++ emailAddresses).traverse{ emailAddress =>
        logger.info(s"Emailing out an update of ${update.packageName} to $emailAddress")
        emailSender.email(
          to = emailAddress,
          subject = update.printEmailTitle,
          content = update.printEmailBody
        )
      }
    } yield ()
  }

  private def updateDependencyInStore(
    update: DependencyUpdate,
    doobieTransactor: Transactor[IO]
  )(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    Persistence.updatePackage(update).transact(doobieTransactor).map(_ => ())
  }

  def consumeRabbitMQ(
    stream: fs2.Stream[IO, AmqpEnvelope[Json]],
    doobieTransactor: Transactor[IO],
    emailSender: EmailSender
  )(implicit contextShift: ContextShift[IO]): fs2.Stream[IO, Unit] = {
    stream
      .map(parseEnvelope)
      .evalTap(dependencyUpdateOrErr => IO(logger.info(
        s"[DEPENDENCY UPDATE] We received an upstream dependency update that looked like the following: $dependencyUpdateOrErr"
      )))
      .evalMap{
        case Right(value) =>
          updateDependencyInStore(value, doobieTransactor)
            .>>(sendEmailAboutDependencyUpdate(value, doobieTransactor, emailSender))
        case Left(PayloadParseFailure(decodeError, json)) => IO(logger.warn(s"We saw this payload parse error!: $decodeError\n$json"))
        case Left(IncorrectRoutingKey(incorrectRoutingKey)) => IO(logger.debug(s"Ignoring this routing key... $incorrectRoutingKey"))
      }
  }

}
