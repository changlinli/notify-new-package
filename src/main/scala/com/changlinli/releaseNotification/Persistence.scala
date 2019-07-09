package com.changlinli.releaseNotification

import java.io.File

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import com.changlinli.releaseNotification.WebServer.{Action, ChangeEmail, EmailAddress, UnsubscribeEmailFromAllPackages}
import doobie._
import doobie.implicits._
import doobie.Transactor
import doobie.util.transactor.Transactor.Aux
import grizzled.slf4j.Logging

object Persistence extends Logging {

  def processAction(action: Action)(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    action match {
      case UnsubscribeEmailFromAllPackages(email) =>
        removeSubscription(email).transact(transactor).map(_ => ())
    }
  }

  def removeSubscription(email: EmailAddress): ConnectionIO[Int] = {
    sql"""DELETE FROM subscriptions WHERE email=${email.str}"""
      .update
      .run
  }

  def transactorA(fileName: String)(implicit contextShift: ContextShift[IO]): Aux[IO, Unit] =
    Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = s"jdbc:sqlite:$fileName",
      user = "",
      pass = ""
    )

  def transactor(implicit contextShift: ContextShift[IO]): Aux[IO, Unit] =
    Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = "jdbc:sqlite:sample.db",
      user = "",
      pass = ""
    )

  def retrieveAllEmailsSubscribedToAll(implicit contextShift: ContextShift[IO]): IO[List[String]] =
    sql"""SELECT email FROM subscriptions WHERE packageName='ALL'"""
      .query[String]
      .to[List]
      .transact(transactor)

  def retrieveAllEmailsWithPackageName(packageName: String)(implicit contextShift: ContextShift[IO]): IO[List[String]] =
    sql"""SELECT email FROM subscriptions WHERE packageName=$packageName"""
      .query[String]
      .to[List]
      .transact(transactor)

  def insertIntoDB(name: String, packageName: String)(implicit contextShift: ContextShift[IO]): ConnectionIO[Int] =
    sql"""INSERT INTO subscriptions (email, packageName) values ($name, $packageName)""".update.run

  val dropSubscriptionsTable: ConnectionIO[Int] =
    sql"""DROP TABLE IF EXISTS subscriptions""".update.run

  val createSubscriptionsTable: ConnectionIO[Int] = sql"""CREATE TABLE subscriptions (
      email TEXT NOT NULL,
      packageName TEXT NOT NULL
    )""".update.run


  def initializeDatabaseFromScratch(filename: String)(implicit contextShift: ContextShift[IO]): IO[Aux[IO, Unit]] = {
    val file = new File(filename)
    for {
      _ <- IO(file.delete())
      _ <- IO(file.createNewFile())
      xactor = transactorA(filename)
      _ <- IO(logger.info("About to initialize database with new tables"))
      _ <- initializeDatabase.transact(xactor)
    } yield xactor
  }

  val initializeDatabase: ConnectionIO[Unit] = for {
    _ <- dropSubscriptionsTable
    _ <- createSubscriptionsTable
  } yield ()
}
