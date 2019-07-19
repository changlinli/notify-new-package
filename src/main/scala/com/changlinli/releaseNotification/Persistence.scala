package com.changlinli.releaseNotification

import java.io.File
import java.util.concurrent.Executors

import cats.data.NonEmptyList
import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import com.changlinli.releaseNotification.WebServer.{Action, ChangeEmail, EmailAddress, FullPackage, PackageName, PersistenceAction, SubscribeToPackages, SubscribeToPackagesFullName, UnsubscribeEmailFromAllPackages, UnsubscribeEmailFromPackage}
import doobie._
import doobie.implicits._
import doobie.Transactor
import doobie.util.transactor.Transactor.Aux
import grizzled.slf4j.Logging
import org.sqlite.javax.SQLiteConnectionPoolDataSource

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object Persistence extends CustomLogging {

  private val createEmailsTable =
    sql"""
         |CREATE TABLE `emails` (
         |`id` INTEGER NOT NULL,
         |`emailAddress` TEXT NOT NULL,
         |UNIQUE(`emailAddress`),
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

  private val subscriptionTableName = "subscriptions"

  private val createSubscriptionsTable =
    sql"""
         |CREATE TABLE `subscriptions` (
         |`id` INTEGER NOT NULL,
         |`emailId` INTEGER NOT NULL,
         |`packageId` INTEGER,
         |`specialType` TEXT,
         |PRIMARY KEY(`id`),
         |FOREIGN KEY(`emailId`) REFERENCES `emails`(`id`),
         |FOREIGN KEY(`packageId`) REFERENCES `packages`(`id`),
         |CHECK (specialType IS NOT NULL or packageId IS NOT NULL)
         |)
       """.stripMargin

//  final case class UnsubscribeEmailFromPackage(email: EmailAddress, pkg: Package) extends EmailAction
//  final case class UnsubscribeEmailFromAllPackages(email: EmailAddress) extends EmailAction
//  final case class ChangeEmail(oldEmail: EmailAddress, newEmail: EmailAddress) extends EmailAction
//  final case class SubscribeToPackages(email: Email, pkgs: NonEmptyList[Package]) extends WebAction
  def processAction(action: PersistenceAction)(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    action match {
      case UnsubscribeEmailFromAllPackages(email) =>
        unsubscribe(email).transact(transactor).void
      case ChangeEmail(oldEmail, newEmail) =>
        changeEmail(oldEmail, newEmail).transact(transactor).void
      case UnsubscribeEmailFromPackage(email, pkg) =>
        unsubscribeEmailFromPackage(email, pkg).transact(transactor).void
      case SubscribeToPackagesFullName(email, pkgs) =>
        subscribeToPackagesFullName(email, pkgs).transact(transactor).void
    }
  }

  def createPackageQuery(packageName: String, homepage: String, anityaId: Int): doobie.Update0 = {
    sql"""INSERT INTO `packages` (name, homepage, anityaId) VALUES ($packageName, $homepage, $anityaId)"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def createPackage(packageName: String, homepage: String, anityaId: Int): ConnectionIO[Int] = {
    createPackageQuery(packageName, homepage, anityaId).run
  }

  def retrievePackages(packageNames: NonEmptyList[PackageName])(implicit contextShift: ContextShift[IO]): ConnectionIO[Map[PackageName, FullPackage]] = {
    val query = fr"""SELECT id, name, homepage, anityaId FROM packages WHERE """ ++
      Fragments.in(fr"name", packageNames.map(_.str))
    query
      .queryWithLogHandler[(Int, String, String, Int)](doobieLogHandler)
      .to[List]
      .map{
        elems =>
          elems
            .map{
              case (id, name, homepage, anityaId) =>
                PackageName(name) -> FullPackage(name = PackageName(name), homepage = homepage, anityaId = anityaId, packageId = id)
            }
            .toMap
      }
  }

  def insertIntoEmailTableIfNotExist(email: EmailAddress): doobie.Update0 = {
    sql"""INSERT OR IGNORE INTO `emails` (emailAddress) VALUES (${email.str})""".updateWithLogHandler(doobieLogHandler)
  }

  def subscribeToPackagesFullName(email: EmailAddress, pkgs: NonEmptyList[FullPackage]): ConnectionIO[Int] = {
    val retrieveEmailId =
      sql"""SELECT `id` FROM `emails` WHERE emailAddress = ${email.str}"""
    def insertIntoSubscriptions(emailId: Int, pkg: FullPackage) =
      sql"""INSERT INTO `subscriptions` (emailId, packageId) VALUES ($emailId, ${pkg.packageId}) """
    for {
      _ <- insertIntoEmailTableIfNotExist(email).run
      // FIXME: This should be a fatal error, but we should have a better error message
      emailId <- retrieveEmailId.queryWithLogHandler[Int](doobieLogHandler).to[List].map{
        case id :: _ => id
        case Nil => throw new Exception(s"This is a programming bug! Because we just inserted an email in this transaction")
      }
      rowNums <- pkgs.traverse(insertIntoSubscriptions(emailId, _).updateWithLogHandler(doobieLogHandler).run)
    } yield rowNums.reduce
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

  def transactorA(fileName: String, executionContext: ExecutionContext, blocker: Blocker)(implicit contextShift: ContextShift[IO]): Transactor[IO] = {
    val config = new org.sqlite.SQLiteConfig()
    config.enforceForeignKeys(true)
    val dataSource = new SQLiteConnectionPoolDataSource()
    dataSource.setUrl(s"jdbc:sqlite:$fileName")
    dataSource.setConfig(config)

    Transactor.fromDataSource[IO](dataSource, executionContext, blocker)
  }

  def transactor(implicit contextShift: ContextShift[IO]): Aux[IO, Unit] =
    Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = "jdbc:sqlite:sample.db",
      user = "",
      pass = ""
    )

  def retrieveAllEmailsSubscribedToAll(implicit contextShift: ContextShift[IO]): IO[List[EmailAddress]] =
    sql"""SELECT email FROM subscriptions WHERE packageName='ALL'"""
      .query[String]
      .map(EmailAddress.apply)
      .to[List]
      .transact(transactor)

  def retrieveAllEmailsWithPackageName(packageName: String)(implicit contextShift: ContextShift[IO]): IO[List[EmailAddress]] =
    sql"""SELECT email FROM subscriptions WHERE packageName=$packageName"""
      .query[String]
      .map(EmailAddress.apply)
      .to[List]
      .transact(transactor)

  def insertIntoDB(name: String, packageName: String)(implicit contextShift: ContextShift[IO]): ConnectionIO[Int] =
    sql"""INSERT INTO subscriptions (email, packageName) values ($name, $packageName)""".update.run

  val dropSubscriptionsTable: ConnectionIO[Int] =
    sql"""DROP TABLE IF EXISTS subscriptions""".update.run

  val initializeDatabase: ConnectionIO[Unit] = for {
    _ <- dropSubscriptionsTable
    _ <- createEmailsTable.updateWithLogHandler(doobieLogHandler).run
    _ <- createPackagesTable.updateWithLogHandler(doobieLogHandler).run
    _ <- createSubscriptionsTable.updateWithLogHandler(doobieLogHandler).run
  } yield ()
}
