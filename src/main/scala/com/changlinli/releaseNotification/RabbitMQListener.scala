package com.changlinli.releaseNotification

import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.changlinli.releaseNotification.data.PackageVersion
import com.changlinli.releaseNotification.ids.AnityaId
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, RoutingKey}
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.{DecodingFailure, Json}

object RabbitMQListener extends CustomLogging {
  private val anityaRoutingKeys: Set[RoutingKey] = Set(
    RoutingKey("org.release-monitoring.prod.anitya.project.version.update"),
    RoutingKey("org.release-monitoring.prod.anitya.project.add"),
    RoutingKey("org.release-monitoring.prod.anitya.project.edit")
  )

  private def parseEnvelope(envelope: AmqpEnvelope[Json]): Either[AppError, JsonPayloadParseResult] = {
    val routingKeySeen = envelope.routingKey
    if (anityaRoutingKeys.contains(routingKeySeen)) {
      envelope.payload.as[JsonPayloadParseResult].left.map(PayloadParseFailure(_, envelope.payload))
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
    Persistence.upsertPackage(
      update.packageName,
      update.homepage,
      update.anityaId,
      PackageVersion(update.packageVersion)
    ).transact(doobieTransactor).map(_ => ())
  }

  private def createNewPackageInStore(
    newPackageCreated: NewPackageCreated,
    doobieTransactor: Transactor[IO]
  )(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    Persistence.upsertPackage(
      newPackageCreated.packageName.str,
      newPackageCreated.homepage,
      newPackageCreated.anityaId.toInt,
      // FIXME: This needs to be dealt with at the DB level as a nullable field
      newPackageCreated.packageVersion.getOrElse(PackageVersion(""))
    ).transact(doobieTransactor).map(_ => ())
  }

  private def editPackageInStore(
    packageEdited: PackageEdited,
    doobieTransactor: Transactor[IO]
  )(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    Persistence.upsertPackage(
      packageEdited.packageName.str,
      packageEdited.homepage,
      packageEdited.anityaId.toInt,
      // FIXME: This needs to be dealt with at the DB level as a nullable field
      packageEdited.packageVersion.getOrElse(PackageVersion(""))
    ).transact(doobieTransactor).map(_ => ())
  }

  def processJsonPayloadResult(
    jsonPayloadParseResult: JsonPayloadParseResult,
    doobieTransactor: Transactor[IO],
    emailSender: EmailSender
  )(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    jsonPayloadParseResult match {
      case value: DependencyUpdate =>
        updateDependencyInStore(value, doobieTransactor)
          .>>(sendEmailAboutDependencyUpdate(value, doobieTransactor, emailSender))
      case value: NewPackageCreated =>
        createNewPackageInStore(value, doobieTransactor)
      case value: PackageEdited =>
        editPackageInStore(value, doobieTransactor)
    }
  }

  def consumeRabbitMQ(
    stream: fs2.Stream[IO, AmqpEnvelope[Json]],
    doobieTransactor: Transactor[IO],
    emailSender: EmailSender
  )(implicit contextShift: ContextShift[IO]): fs2.Stream[IO, Unit] = {
    stream
      .map(parseEnvelope)
      .evalMap{
        case Right(value) =>
          IO(logger.info(
            s"[DEPENDENCY UPDATE] We received an upstream dependency update that looked like the following: $value"
          )).>>{
            processJsonPayloadResult(value, doobieTransactor, emailSender)
          }
        case Left(PayloadParseFailure(decodeError, json)) => IO(logger.warn(s"We saw this payload parse error!: $decodeError\n$json"))
        case Left(IncorrectRoutingKey(incorrectRoutingKey)) => IO(logger.debug(s"Ignoring this routing key... $incorrectRoutingKey"))
      }
  }

}
