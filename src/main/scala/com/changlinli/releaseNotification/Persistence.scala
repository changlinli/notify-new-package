package com.changlinli.releaseNotification

import java.nio.charset.Charset
import java.security.SecureRandom
import java.time.Instant
import java.util.Properties

import cats.Id
import cats.data.{Ior, Kleisli, NonEmptyList}
import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.implicits._
import com.changlinli.releaseNotification.WebServer._
import com.changlinli.releaseNotification.data.{ConfirmationCode, EmailAddress, FullPackage, PackageName, PackageVersion, UnsubscribeCode}
import com.changlinli.releaseNotification.ids.{AnityaId, EmailId, PackageId, SubscriptionId}
import doobie.{Transactor, _}
import doobie.implicits._
import org.sqlite.SQLiteConfig
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
         |`currentVersion` TEXT NOT NULL,
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
         |`createdTime` INTEGER NOT NULL,
         |`confirmationCode` TEXT NOT NULL,
         |`confirmedTime` INTEGER,
         |PRIMARY KEY(`id`),
         |FOREIGN KEY(`emailId`) REFERENCES `emails`(`id`),
         |FOREIGN KEY(`packageId`) REFERENCES `packages`(`id`),
         |CHECK (specialType IS NOT NULL or packageId IS NOT NULL),
         |UNIQUE(emailId, packageId)
         |)
       """.stripMargin

  private val createUnsubscribeCodesTable =
    sql"""
         |CREATE TABLE IF NOT EXISTS `unsubscribeCodes` (
         |`id` INTEGER NOT NULL,
         |`code` TEXT NOT NULL,
         |`subscriptionId` INTEGER NOT NULL,
         |PRIMARY KEY(`id`),
         |FOREIGN KEY(`subscriptionId`) REFERENCES `subscriptions`(`id`),
         |UNIQUE (subscriptionId),
         |UNIQUE (code)
         |)
       """.stripMargin

  type WithInstantT[F[_], A] = Kleisli[F, Instant, A]

  type WithInstant[A] = WithInstantT[Id, A]

  private def createUnsubscribeCodeQuery(freshCode: UnsubscribeCode, subscription: SubscriptionId): doobie.Update0 = {
    sql"""
         |INSERT INTO `unsubscribeCodes` (code, subscriptionId) VALUES (${freshCode.str}, ${subscription.toInt})
       """.stripMargin
      .updateWithLogHandler(doobieLogHandler)
  }

  def getUnsubscribeCodeForSubscriptionQuery(subscriptionId: SubscriptionId): doobie.Query0[String] = {
    sql"""SELECT code FROM unsubscribeCodes WHERE subscriptionId = ${subscriptionId.toInt}"""
      .stripMargin
      .queryWithLogHandler[String](doobieLogHandler)
  }

  def getUnsubscribeCodeForSubscription(subscriptionId: SubscriptionId): ConnectionIO[Option[UnsubscribeCode]] = {
    getUnsubscribeCodeForSubscriptionQuery(subscriptionId)
      // Note that we assume that we have a valid unsubscribe code since we're
      // getting it directly from the DB, which is why we use the
      // unsafeFromString method here
      .map(UnsubscribeCode.unsafeFromString)
      .to[List]
      .map(_.headOption)
  }

  def unsubscribeUsingCodeQuery(code: UnsubscribeCode): doobie.Update0 = {
    sql"""DELETE FROM `subscriptions` WHERE
         |subscriptions.id IN (
         |  SELECT unsubscribeCodes.subscriptionId FROM `unsubscribeCodes` WHERE
         |    subscriptions.id = unsubscribeCodes.subscriptionId
         |)""".stripMargin.updateWithLogHandler(doobieLogHandler)
  }

  def getSubscriptionIdFromCodeQuery(code: UnsubscribeCode): doobie.Query0[Int] = {
    sql"""SELECT subscriptionId FROM unsubscribeCodes WHERE code = ${code.str}"""
      .queryWithLogHandler[Int](doobieLogHandler)
  }

  def getSubsciptionIdFromCode(code: UnsubscribeCode): ConnectionIO[Option[SubscriptionId]] = {
    getSubscriptionIdFromCodeQuery(code).to[List].map(_.headOption.map(SubscriptionId.apply))
  }

  private def confirmSubscriptionQuery(confirmationCode: ConfirmationCode, currentTime: Instant): doobie.Update0 = {
    sql"""UPDATE `subscriptions` SET confirmedTime=${currentTime.getEpochSecond} WHERE confirmationCode=${confirmationCode.str}"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def confirmSubscription(confirmationCode: ConfirmationCode, currentTime: Instant): doobie.ConnectionIO[Int] = {
    confirmSubscriptionQuery(confirmationCode, currentTime).run
  }

  def retrievePackageAssociatedWithSubscriptionId(subscriptionId: SubscriptionId): ConnectionIO[Option[FullPackage]] = {
    sql"""SELECT packages.id, packages.name, packages.homepage, packages.anityaId, packages.currentVersion FROM `packages`
         |INNER JOIN `subscriptions` ON subscriptions.packageId = packages.id
         |WHERE subscriptions.id = ${subscriptionId.toInt}"""
      .stripMargin
      .queryWithLogHandler[(Int, String, String, Int, String)](doobieLogHandler)
      .to[List]
      .map(_.headOption)
      .map{
        _.map{
          case (packageId, name, homepage, anityaId, currentVersion) =>
            FullPackage(
              name = PackageName(name),
              homepage = homepage,
              anityaId = anityaId,
              packageId = packageId,
              currentVersion = PackageVersion(currentVersion)
            )
        }
      }
  }

  def retrievePackageAssociatedWithCode(code: UnsubscribeCode): ConnectionIO[Option[FullPackage]] = {
    for {
      subscriptionIdOpt <- getSubsciptionIdFromCode(code)
      result <- subscriptionIdOpt
        .traverse(retrievePackageAssociatedWithSubscriptionId)
        .map(_.flatten)
    } yield result
  }

  def unsubscribeUsingCode(code: UnsubscribeCode): doobie.ConnectionIO[Option[(FullPackage, EmailAddress)]] = {
    for {
      subscriptionIdOpt <- getSubsciptionIdFromCode(code)
      _ <- markUnsubscribeCodeAsUsedQuery(code).run
      affectedPackageAndEmail <- subscriptionIdOpt
        .traverse(unsubscribeUsingSubscriptionId)
        .map(_.flatten)
    } yield affectedPackageAndEmail
  }

  def markUnsubscribeCodeAsUsedQuery(code: UnsubscribeCode): doobie.Update0 = {
    sql"""DELETE FROM unsubscribeCodes WHERE code=${code.str}"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def processAction(
    action: PersistenceAction,
    transactor: Transactor[IO]
  )(implicit contextShift: ContextShift[IO]): IO[Unit] = {
    action match {
      case UnsubscribeEmailFromAllPackages(email) =>
        unsubscribe(email).transact(transactor).void
      case ChangeEmail(oldEmail, newEmail) =>
        changeEmail(oldEmail, newEmail).transact(transactor).void
      case UnsubscribeEmailFromPackage(email, pkg) =>
        unsubscribeEmailFromPackage(email, pkg).transact(transactor).void
      case SubscribeToPackagesFullName(email, pkgs) =>
        val zippedPkgs = pkgs.traverse(pkg => UnsubscribeCode.generateUnsubscribeCode.map((pkg, _)))
        zippedPkgs.flatMap{
          pkgsWithUnsubscribeCodes =>
            for {
              currentTime <- IO(Instant.now())
              confirmationCode <- ConfirmationCode.generateConfirmationCode
              _ <- subscribeToPackagesFullName(email, pkgsWithUnsubscribeCodes, currentTime, confirmationCode)
                .transact(transactor)
                .void
            } yield ()
        }
    }
  }

  def searchForPackagesByNameFragmentQuery(nameFragment: String): doobie.Query0[(Int, String, String, Int, String)] = {
    sql"""SELECT id, name, homepage, anityaId, currentVersion FROM `packages` WHERE name LIKE ${s"%$nameFragment%"} LIMIT 10"""
      .queryWithLogHandler[(Int, String, String, Int, String)](doobieLogHandler)
  }

  def retrievePackageByAnityaIdQuery(anityaId: Int): doobie.Query0[(Int, String, String, Int, String)] = {
    sql"""SELECT id, name, homepage, anityaId, currentVersion FROM `packages` WHERE anityaId = $anityaId"""
      .queryWithLogHandler[(Int, String, String, Int, String)](doobieLogHandler)
  }

  def insertPackageQuery(packageName: String, homepage: String, anityaId: Int, currentVersion: PackageVersion): doobie.Update0 = {
    sql"""INSERT INTO `packages` (name, homepage, anityaId, currentVersion) VALUES ($packageName, $homepage, $anityaId, ${currentVersion.str})"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def updatePackageQuery(id: Int, packageName: String, homepage: String, anityaId: Int, currentVersion: PackageVersion): doobie.Update0 = {
    sql"""UPDATE `packages` SET name=$packageName, homepage=$homepage, anityaId=$anityaId, currentVersion=${currentVersion.str} WHERE id=$id"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def searchForPackagesByNameFragment(nameFragment: String): ConnectionIO[List[FullPackage]] = {
    searchForPackagesByNameFragmentQuery(nameFragment)
      .to[List]
      .map(_.map{
        case (id, name, homepage, anityaId, currentVersion) =>
          FullPackage(
            name = PackageName(name),
            homepage = homepage,
            anityaId = anityaId,
            packageId = id,
            currentVersion = PackageVersion(currentVersion)
          )
      })
  }

  private def updatePackageVersionQuery(anityaId: AnityaId, newVersion: PackageVersion): doobie.Update0 = {
    sql"""UPDATE `packages` SET currentVersion=${newVersion.str} WHERE anityaId=${anityaId.toInt}"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def updatePackageVersion(anityaId: AnityaId, newVersion: PackageVersion): ConnectionIO[Int] = {
    updatePackageVersionQuery(anityaId, newVersion).run
  }

  def upsertPackage(packageName: String, homepage: String, anityaId: Int, version: PackageVersion): ConnectionIO[Int] = {
    for {
      listOfIdNameHomepageAnityaId <- retrievePackageByAnityaIdQuery(anityaId).to[List]
      firstElemOpt = listOfIdNameHomepageAnityaId.headOption
      result <- firstElemOpt match {
        case Some((currentId, currentName, currentHomepage, currentAnityaId, currentVersion)) =>
          updatePackageQuery(currentId, currentName, currentHomepage, currentAnityaId, PackageVersion(currentVersion)).run
        case None =>
          insertPackageQuery(packageName, homepage, anityaId, version).run
      }
    } yield result
  }

  def retrievePackages(anityaIds: NonEmptyList[AnityaId]): ConnectionIO[Map[AnityaId, FullPackage]] = {
    val query =
      fr"""
          |SELECT packages.id, packages.name, packages.homepage, packages.anityaId, packages.currentVersion FROM packages
          | WHERE """.stripMargin ++
      Fragments.in(fr"packages.anityaId", anityaIds.map(_.toInt))
    query
      .queryWithLogHandler[(Int, String, String, Int, String)](doobieLogHandler)
      .to[List]
      .map{
        elems =>
          elems
            .map{
              case (id, name, homepage, anityaId, currentVersion) =>
                AnityaId(anityaId) ->
                  FullPackage(
                    name = PackageName(name),
                    homepage = homepage,
                    anityaId = anityaId,
                    packageId = id,
                    currentVersion = PackageVersion(currentVersion)
                  )
            }
            .toMap
      }
  }

  def insertIntoEmailTableIfNotExist(email: EmailAddress): doobie.Update0 = {
    sql"""INSERT OR IGNORE INTO `emails` (emailAddress) VALUES (${email.str})""".updateWithLogHandler(doobieLogHandler)
  }

  def packageIdsInSubscriptionWithEmail(email: EmailId): doobie.ConnectionIO[List[(SubscriptionId, PackageId)]] = {
    sql"""SELECT id, packageId FROM `subscriptions` WHERE emailId=${email.toInt}"""
      .queryWithLogHandler[(Int, Int)](doobieLogHandler)
      .map{case (subId, pkgId) => (SubscriptionId(subId), PackageId(pkgId))}
      .to[List]
  }

  def subscribeToPackagesFullName(
    email: EmailAddress,
    pkgsWithUnsubscribeCodes: NonEmptyList[(FullPackage, UnsubscribeCode)],
    currentTime: Instant,
    confirmationCode: ConfirmationCode
  ): ConnectionIO[Ior[SubscriptionsAlreadyExistErr, Int]] = {
    val pkgs = pkgsWithUnsubscribeCodes.map(_._1)
    val retrieveEmailId =
      sql"""SELECT `id` FROM `emails` WHERE emailAddress = ${email.str}"""
    def insertIntoSubscriptions(emailId: Int, pkg: FullPackage) =
      sql"""INSERT OR IGNORE INTO `subscriptions` (emailId, packageId, createdTime, confirmationCode) VALUES
           |($emailId, ${pkg.packageId}, ${currentTime.getEpochSecond}, ${confirmationCode.str}) """.stripMargin
    def retrieveSubscriptionId(emailId: Int, pkgId: PackageId) =
      sql"""SELECT `id` FROM `subscriptions` WHERE emailId = $emailId AND packageId=${pkgId.toInt}"""
    for {
      _ <- insertIntoEmailTableIfNotExist(email).run
      // FIXME: This should be a fatal error, but we should have a better error message
      emailId <- retrieveEmailId.queryWithLogHandler[Int](doobieLogHandler).to[List].map{
        case id :: _ => id
        case Nil => throw new Exception(s"This is a programming bug! Because we just inserted an email in this transaction")
      }
      subAndPkgIds <- packageIdsInSubscriptionWithEmail(EmailId(emailId))
      pkgIdsToPkgs = pkgs.groupBy(_.packageId).view.mapValues(_.head).toMap
      pkgToExistingSubId = subAndPkgIds.map(_.swap).toMap
      pkgIdsToPkgsExistingSub = pkgIdsToPkgs
        .transform{case (key, value) => pkgToExistingSubId.get(PackageId(key)).map((_, value))}
        .toList
        .collect{case (key, Some(value)) => (key, value)}
        .toMap
      insertedOrErr <- pkgs.traverse{pkg =>
        pkgIdsToPkgsExistingSub.get(pkg.packageId) match {
          case Some((subId, fullPackage)) =>
            Either.left[SubscriptionAlreadyExists, FullPackage](
              SubscriptionAlreadyExists(subId, fullPackage, email)
            ).pure[ConnectionIO]
          case None =>
            insertIntoSubscriptions(emailId, pkg)
              .updateWithLogHandler(doobieLogHandler)
              .run
              .as(Either.right[SubscriptionAlreadyExists, FullPackage](pkg))
        }
      }
      insertedPkgs = insertedOrErr.collect{case Right(pkg) => pkg}.toSet
      insertedPkgsWithUnsubscribeCodes = pkgsWithUnsubscribeCodes.filter{case (pkg, _) => insertedPkgs.contains(pkg)}
      _ <- insertedPkgsWithUnsubscribeCodes
        .traverse{
          case (pkg, unsubscribeCode) => retrieveSubscriptionId(emailId, PackageId(pkg.packageId))
            .queryWithLogHandler[Int](doobieLogHandler)
            .to[List]
            // FIXME: This should be a fatal error, but we should have a better error message
            .map{
              _.headOption
                .getOrElse(throw new Exception(s"This is a programming bug! Because we just inserted the subscription in this transaction (or verified that this subscription already exists!)"))
            }
            .map((_, unsubscribeCode))
        }
        .flatMap{
          subscriptionIds => subscriptionIds
            .traverse{case (subscriptionId, unsubscribeCode) => createUnsubscribeCodeQuery(unsubscribeCode, SubscriptionId(subscriptionId)).run}
        }
    } yield {
      insertedOrErr
        .traverse{
          case Left(err) => Ior.leftNel[SubscriptionAlreadyExists, FullPackage](err)
          case Right(pkg) => Ior.right[NonEmptyList[SubscriptionAlreadyExists], FullPackage](pkg)
        }
        .map(_.length).leftMap(SubscriptionsAlreadyExistErr.apply)
    }
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

  def unsubscribeUsingSubscriptionId(subscriptionId: SubscriptionId): ConnectionIO[Option[(FullPackage, EmailAddress)]] = {
    val associatedEmail =
      sql"""SELECT emails.emailAddress FROM emails
           |INNER JOIN subscriptions ON subscriptions.emailId = emails.id
           |WHERE subscriptions.id = ${subscriptionId.toInt}""".stripMargin
        .queryWithLogHandler[String](doobieLogHandler)
        // We can use unsafeFromString because we assume that it's a valid email if it made it into the database
        .map{emailStr => EmailAddress.unsafeFromString(emailStr)}
        .to[List]
        .map(_.headOption)
    val retrievePackages = sql"""SELECT packages.id, packages.name, packages.homepage, packages.anityaId, packages.currentVersion FROM subscriptions
         |INNER JOIN packages ON packages.id = subscriptions.packageId
         |WHERE subscriptions.id = ${subscriptionId.toInt}""".stripMargin
      .queryWithLogHandler[(Int, String, String, Int, String)](doobieLogHandler)
      .map{case (id, name, homepage, anityaId, currentVersion) => FullPackage(
        packageId = id,
        name = PackageName(name),
        homepage = homepage,
        anityaId = anityaId,
        currentVersion = PackageVersion(currentVersion))
      }
      .to[List]
      .map(_.headOption)
    val deleteSubscription = sql"""DELETE FROM subscriptions WHERE id = ${subscriptionId.toInt}"""
      .updateWithLogHandler(doobieLogHandler)
      .run
    for {
      email <- associatedEmail
      pkgAffected <- retrievePackages
      _ <- deleteSubscription
    } yield pkgAffected.map2(email){(_, _)}
  }

  def unsubscribeEmailFromPackage(email: EmailAddress, packageName: PackageName): ConnectionIO[Int] = {
    sql"""DELETE FROM subscriptions WHERE id IN
         |  (SELECT subscriptions.id FROM emails INNER JOIN subscriptions ON emails.id = subscriptions.emailId WHERE emailAddress=${email.str}))
         |AND packageId IN (SELECT id FROM packages WHERE name = ${packageName.str})"""
      .update
      .run
  }

  def persistRawAnityaProject(rawAnityaProject: RawAnityaProject): ConnectionIO[Int] = {
    upsertPackage(rawAnityaProject.name, rawAnityaProject.homepage, rawAnityaProject.id, PackageVersion(rawAnityaProject.version.getOrElse("")))
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
      // We assume if the email address is in the database it's a valid string
      .map(EmailAddress.unsafeFromString)
      .to[List]

  def retrieveAllEmailsSubscribedToAll(transactor: Transactor[IO])(implicit contextShift: ContextShift[IO]): IO[List[EmailAddress]] =
    retrieveAllEmailsSubscribedToAllA.transact(transactor)

  def retrieveAllEmailsWithAnityaIdA(anityaId: Int): ConnectionIO[List[EmailAddress]] =
    sql"""SELECT emailAddress FROM subscriptions INNER JOIN emails ON emails.id = subscriptions.emailId INNER JOIN packages ON packages.id = subscriptions.packageId WHERE packages.anityaId=$anityaId"""
      .query[String]
      // We assume if the email address is in the database it's a valid string
      .map(EmailAddress.unsafeFromString)
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
