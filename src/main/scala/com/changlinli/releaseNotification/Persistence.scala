package com.changlinli.releaseNotification

import java.io.File

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import com.changlinli.releaseNotification.WebServer.{Action, ChangeEmail, EmailAddress, FullPackage, PackageName, PersistenceAction, SubscribeToPackages, SubscribeToPackagesFullName, UnsubscribeEmailFromAllPackages, UnsubscribeEmailFromPackage}
import doobie._
import doobie.implicits._
import doobie.Transactor
import doobie.util.transactor.Transactor.Aux
import grizzled.slf4j.Logging

object Persistence extends Logging {

  private val createEmailsTable =
    sql"""
         |CREATE TABLE `emails` ( `id` INTEGER NOT NULL, `emailAddress` TEXT NOT NULL, PRIMARY KEY(`id`) )
       """.stripMargin

  private val createPackageSubscriptionsTable =
    sql"""
         |CREATE TABLE `packageSubscriptions` (
         |`id` INTEGER NOT NULL,
         |`packageId` INTEGER NOT NULL,
         |FOREIGN KEY(`packageId`) REFERENCES `packages`(`id`),
         |FOREIGN KEY(`id`) REFERENCES `subscriptions`(`id`),
         |PRIMARY KEY(`id`)
         |)
       """.stripMargin

  private val createPackagesTable =
    sql"""
         |CREATE TABLE `packages` (
         |`id` INTEGER NOT NULL,
         |`name` TEXT NOT NULL,
         |`homepage` TEXT NOT NULL,
         |`anityaId` INTEGER NOT NULL,
         |PRIMARY KEY(`id`)
         |)
       """.stripMargin

  private val createSpecialSubscriptionsTable =
    sql"""
         |CREATE TABLE `specialSubscriptions` (
         |`id` INTEGER NOT NULL,
         |`type` TEXT NOT NULL,
         |FOREIGN KEY(`id`) REFERENCES `subscriptions`(`id`),
         |PRIMARY KEY(`id`)
         |)
       """.stripMargin

  private val subscriptionTableName = "subscriptions"

  private val createSubscriptionsTable =
    sql"""
         |CREATE TABLE `subscriptions` (
         |`id` INTEGER NOT NULL,
         |`emailId` INTEGER NOT NULL,
         |PRIMARY KEY(`id`),
         |FOREIGN KEY(`emailId`) REFERENCES `emails`(`id`)
         |)
       """.stripMargin

//  final case class UnsubscribeEmailFromPackage(email: EmailAddress, pkg: Package) extends EmailAction
//  final case class UnsubscribeEmailFromAllPackages(email: EmailAddress) extends EmailAction
//  final case class ChangeEmail(oldEmail: EmailAddress, newEmail: EmailAddress) extends EmailAction
//  final case class SubscribeToPackages(email: Email, pkgs: NonEmptyList[Package]) extends WebAction
  def processAction(action: PersistenceAction)(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    action match {
      case UnsubscribeEmailFromAllPackages(email) =>
        unsubscribe(email).transact(transactor).map(_ => ())
      case ChangeEmail(oldEmail, newEmail) =>
        changeEmail(oldEmail, newEmail).transact(transactor).map(_ => ())
      case UnsubscribeEmailFromPackage(email, pkg) =>
        unsubscribeEmailFromPackage(email, pkg).transact(transactor).map(_ => ())
      case SubscribeToPackagesFullName(email, pkgs) =>
        ???
    }
  }

  def subscribeToPackagesFullName(email: EmailAddress, pkgs: NonEmptyList[FullPackage]): ConnectionIO[Int] = {
    ???
  }

  def changeEmail(oldEmail: EmailAddress, newEmail: EmailAddress): ConnectionIO[Int] = {
    sql"""UPDATE `emails` SET emailAddress = ${newEmail.str} WHERE emailAddress = ${oldEmail.str}"""
      .update
      .run
  }

  def unsubscribe(email: EmailAddress): ConnectionIO[Int] = {
    sql"""DELETE FROM subscriptions WHERE emailId IN (SELECT id FROM emails WHERE emailAddress=${email.str})"""
      .update
      .run
  }

  def unsubscribeEmailFromPackage(email: EmailAddress, packageName: PackageName): ConnectionIO[Int] = {
    sql"""DELETE FROM packageSubscriptions WHERE id IN
         |  (|SELECT subscriptions.id FROM emails INNER JOIN subscriptions ON emails.id = subscriptions.emailId WHERE emailAddress=${email.str}))
         |AND packageId IN (SELECT id FROM packages WHERE name = ${packageName.str})"""
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
    _ <- createSubscriptionsTable.update.run
  } yield ()
}
