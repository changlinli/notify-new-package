package com.changlinli.releaseNotification

import java.util.concurrent.TimeUnit

import cats.effect.{IO, Timer}
import doobie.implicits._
import doobie.util.transactor.Transactor

import scala.concurrent.duration.FiniteDuration

object GarbageDataCollection extends CustomLogging {

  def removeAllUnusedEmailAddresses(transactor: Transactor[IO]): IO[Unit] =
    Persistence
      .deleteUnusedEmailAddresses
      .transact(transactor)
      .flatMap{
        emailIdsAndAddressesDeleted =>
          IO(logger.info(s"We deleted the following inactive emails: ${emailIdsAndAddressesDeleted.mkString(",")}"))
      }

  def periodicallyCleanUp(transactor: Transactor[IO])(implicit timer: Timer[IO]): fs2.Stream[IO, Unit] =
    fs2.Stream.fixedRate[IO](FiniteDuration(1, TimeUnit.HOURS)).evalMap{
      _ => removeAllUnusedEmailAddresses(transactor)
    }

}
