package com.changlinli.releaseNotification

import java.nio.charset.Charset
import java.security.SecureRandom

import cats.data.NonEmptyList
import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.implicits._
import com.changlinli.releaseNotification.WebServer._
import com.changlinli.releaseNotification.data.{FullPackage, PackageName, UnsubscribeCode}
import com.changlinli.releaseNotification.ids.AnityaId
import doobie.{Transactor, _}
import doobie.implicits._
import org.sqlite.SQLiteConfig.JournalMode
import org.sqlite.javax.SQLiteConnectionPoolDataSource

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object Persistence extends CustomLogging {

  private val createEmailsTable =
    sql"""
         |CREATE TABLE IF NOT EXISTS `emails` (
         |`id` INTEGER NOT NULL,
         |`emailAddress` TEXT NOT NULL,
         |UNIQUE(`emailAddress`),
         |PRIMARY KEY(`id`)
         |)
       """.stripMargin

  private val createPackagesTable =
    sql"""
         |CREATE TABLE IF NOT EXISTS `packages` (
         |`id` INTEGER NOT NULL,
         |`name` TEXT NOT NULL,
         |`homepage` TEXT NOT NULL,
         |`anityaId` INTEGER NOT NULL,
         |PRIMARY KEY(`id`)
         |)
       """.stripMargin

  private val createAnityaIdIndex =
    sql"""
         |CREATE INDEX IF NOT EXISTS anityaIdIndex ON packages(anityaId)
       """.stripMargin

  private val createSubscriptionsTable =
    sql"""
         |CREATE TABLE IF NOT EXISTS `subscriptions` (
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

  private val createUnsubscribeCodesTable =
    sql"""
         |CREATE TABLE IF NOT EXISTS `unsubscribeCodes` (
         |`id` INTEGER NOT NULL,
         |`code` TEXT NOT NULL,
         |`subscriptionId` INTEGER NOT NULL,
         |PRIMARY KEY(`id`),
         |FOREIGN KEY(`subscriptionId`) REFERENCES `subscriptions`(`id`)
         |)
       """.stripMargin

  def processAction(action: PersistenceAction, transactor: Transactor[IO])(implicit contextShift: ContextShift[IO]): IO[Unit] = {
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

  def searchForPackagesByNameFragmentQuery(nameFragment: String): doobie.Query0[(Int, String, String, Int)] = {
    sql"""SELECT id, name, homepage, anityaId FROM `packages` WHERE name LIKE ${s"%$nameFragment%"} LIMIT 10"""
      .queryWithLogHandler[(Int, String, String, Int)](doobieLogHandler)
  }

  def retrievePackageByAnityaIdQuery(anityaId: Int): doobie.Query0[(Int, String, String, Int)] = {
    sql"""SELECT id, name, homepage, anityaId FROM `packages` WHERE anityaId = $anityaId"""
      .queryWithLogHandler[(Int, String, String, Int)](doobieLogHandler)
  }

  def insertPackageQuery(packageName: String, homepage: String, anityaId: Int): doobie.Update0 = {
    sql"""INSERT INTO `packages` (name, homepage, anityaId) VALUES ($packageName, $homepage, $anityaId)"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def updatePackageQuery(id: Int, packageName: String, homepage: String, anityaId: Int): doobie.Update0 = {
    sql"""UPDATE `packages` set name=$packageName, homepage=$homepage, anityaId=$anityaId WHERE id=$id"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def searchForPackagesByNameFragment(nameFragment: String): ConnectionIO[List[FullPackage]] = {
    searchForPackagesByNameFragmentQuery(nameFragment)
      .to[List]
      .map(_.map{
        case (id, name, homepage, anityaId) =>
          FullPackage(name = PackageName(name), homepage = homepage, anityaId = anityaId, packageId = id)
      })
  }

  def upsertPackage(packageName: String, homepage: String, anityaId: Int): ConnectionIO[Int] = {
    for {
      listOfIdNameHomepageAnityaId <- retrievePackageByAnityaIdQuery(anityaId).to[List]
      firstElemOpt = listOfIdNameHomepageAnityaId.headOption
      result <- firstElemOpt match {
        case Some((currentId, currentName, currentHomepage, currentAnityaId)) =>
          updatePackageQuery(currentId, currentName, currentHomepage, currentAnityaId).run
        case None =>
          insertPackageQuery(packageName, homepage, anityaId).run
      }
    } yield result
  }

  def retrievePackages(anityaIds: NonEmptyList[AnityaId])(implicit contextShift: ContextShift[IO]): ConnectionIO[Map[AnityaId, FullPackage]] = {
    val query = fr"""SELECT id, name, homepage, anityaId FROM packages WHERE """ ++
      Fragments.in(fr"anityaId", anityaIds.map(_.toInt))
    query
      .queryWithLogHandler[(Int, String, String, Int)](doobieLogHandler)
      .to[List]
      .map{
        elems =>
          elems
            .map{
              case (id, name, homepage, anityaId) =>
                AnityaId(anityaId) -> FullPackage(name = PackageName(name), homepage = homepage, anityaId = anityaId, packageId = id)
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
    sql"""DELETE FROM subscriptions WHERE id IN
         |  (SELECT subscriptions.id FROM emails INNER JOIN subscriptions ON emails.id = subscriptions.emailId WHERE emailAddress=${email.str}))
         |AND packageId IN (SELECT id FROM packages WHERE name = ${packageName.str})"""
      .update
      .run
  }

  def persistRawAnityaProject(rawAnityaProject: RawAnityaProject): ConnectionIO[Int] = {
    upsertPackage(rawAnityaProject.name, rawAnityaProject.homepage, rawAnityaProject.id)
  }

  def createTransactor(fileName: String)(implicit contextShift: ContextShift[IO]): Resource[IO, Transactor[IO]] = {
    for {
      connectionEC <- ExecutionContexts.fixedThreadPool[IO](32)
      transactionEC <- ExecutionContexts.cachedThreadPool[IO]
      blocker = Blocker.liftExecutionContext(transactionEC)
    } yield createTransactorRaw(fileName, connectionEC, blocker)
  }

  def createTransactorRaw(fileName: String, connectionEC: ExecutionContext, blocker: Blocker)(implicit contextShift: ContextShift[IO]): Transactor[IO] = {
    val config = new org.sqlite.SQLiteConfig()
    config.enforceForeignKeys(true)
    config.setJournalMode(JournalMode.WAL)
    val dataSource = new SQLiteConnectionPoolDataSource()
    dataSource.setUrl(s"jdbc:sqlite:$fileName")
    dataSource.setConfig(config)

    Transactor.fromDataSource[IO](dataSource, connectionEC, blocker)
  }

  val retrieveAllEmailsSubscribedToAllA: ConnectionIO[List[EmailAddress]] =
    sql"""SELECT emailAddress FROM subscriptions INNER JOIN emails ON emails.id = subscriptions.emailId WHERE specialType='ALL'"""
      .query[String]
      .map(EmailAddress.apply)
      .to[List]

  def retrieveAllEmailsSubscribedToAll(transactor: Transactor[IO])(implicit contextShift: ContextShift[IO]): IO[List[EmailAddress]] =
    retrieveAllEmailsSubscribedToAllA.transact(transactor)

  def retrieveAllEmailsWithAnityaIdA(anityaId: Int): ConnectionIO[List[EmailAddress]] =
    sql"""SELECT emailAddress FROM subscriptions INNER JOIN emails ON emails.id = subscriptions.emailId INNER JOIN packages ON packages.id = subscriptions.packageId WHERE packages.anityaId=$anityaId"""
      .query[String]
      .map(EmailAddress.apply)
      .to[List]

  def retrieveAllEmailsWithAnityaId(
    transactor: Transactor[IO],
    anityaId: Int)(
    implicit contextShift: ContextShift[IO]
  ): IO[List[EmailAddress]] =
    retrieveAllEmailsWithAnityaIdA(anityaId).transact(transactor)

  def insertIntoDB(name: String, packageName: String)(implicit contextShift: ContextShift[IO]): ConnectionIO[Int] =
    sql"""INSERT INTO subscriptions (email, packageName) values ($name, $packageName)""".update.run

  val dropSubscriptionsTable: ConnectionIO[Int] =
    sql"""DROP TABLE IF EXISTS subscriptions""".update.run

  val initializeDatabase: ConnectionIO[Unit] = for {
    _ <- dropSubscriptionsTable
    _ <- createEmailsTable.updateWithLogHandler(doobieLogHandler).run
    _ <- createPackagesTable.updateWithLogHandler(doobieLogHandler).run
    _ <- createSubscriptionsTable.updateWithLogHandler(doobieLogHandler).run
    _ <- createAnityaIdIndex.updateWithLogHandler(doobieLogHandler).run
    _ <- createUnsubscribeCodesTable.updateWithLogHandler(doobieLogHandler).run
  } yield ()
}
