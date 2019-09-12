package com.changlinli.releaseNotification

import java.time.Instant

import cats.Id
import cats.data.{Ior, IorT, Kleisli, NonEmptyList}
import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.implicits._
import com.changlinli.releaseNotification.WebServer._
import com.changlinli.releaseNotification.data.{ConfirmationCode, EmailAddress, FullPackage, PackageName, PackageVersion, UnsubscribeCode}
import com.changlinli.releaseNotification.errors.SubscriptionAlreadyExists
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

  def retrieveRelevantSubscriptionInfo(confirmationCode: ConfirmationCode): doobie.ConnectionIO[List[(FullPackage, UnsubscribeCode, EmailAddress)]] = {
    sql"""SELECT packages.id, packages.name, packages.homepage, packages.anityaId, packages.currentVersion, unsubscribeCodes.code, emails.emailAddress
         |FROM subscriptions
         |INNER JOIN packages ON packages.id = subscriptions.packageId
         |INNER JOIN unsubscribeCodes ON unsubscribeCodes.subscriptionId = subscriptions.id
         |INNER JOIN emails ON emails.id = subscriptions.emailId
         |WHERE subscriptions.confirmationCode = ${confirmationCode.str}"""
      .stripMargin
      .queryWithLogHandler[(Int, String, String, Int, String, String, String)](doobieLogHandler)
      .map{case (packageId, packageName, homepage, anityaId, currentVersion, unsubscribeCode, emailAddress) =>
        // We use unsafe conversions here because we assume that things were stored correctly in the DB
        (
          FullPackage(PackageName(packageName), homepage, anityaId, packageId, PackageVersion(currentVersion)),
          UnsubscribeCode.unsafeFromString(unsubscribeCode),
          EmailAddress.unsafeFromString(emailAddress)
        )
      }
      .to[List]
  }

  def confirmSubscription(confirmationCode: ConfirmationCode, currentTime: Instant): doobie.ConnectionIO[List[(FullPackage, UnsubscribeCode, EmailAddress)]] = {
    val retrieveRelevantInfo = retrieveRelevantSubscriptionInfo(confirmationCode)
    for {
      info <- retrieveRelevantInfo
      _ <- confirmSubscriptionQuery(confirmationCode, currentTime).run
    } yield info
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

  def searchForPackagesByExactName(name: String): doobie.Query0[(Int, String, String, Int, String)] = {
    sql"""SELECT id, name, homepage, anityaId, currentVersion FROM `packages` WHERE name = $name COLLATE NOCASE"""
      .queryWithLogHandler[(Int, String, String, Int, String)](doobieLogHandler)
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
    val searchForExactMatches = searchForPackagesByExactName(nameFragment).to[List]
    val searchForPartialMatches = searchForPackagesByNameFragmentQuery(nameFragment).to[List]
    for {
      exactMatches <- searchForExactMatches
      partialMatches <- searchForPartialMatches
    } yield {
      (exactMatches ++ partialMatches)
        .map{
          case (id, name, homepage, anityaId, currentVersion) =>
            FullPackage(
              name = PackageName(name),
              homepage = homepage,
              anityaId = anityaId,
              packageId = id,
              currentVersion = PackageVersion(currentVersion)
            )
        }
    }
  }

  private def updatePackageVersionQuery(anityaId: AnityaId, newVersion: PackageVersion): doobie.Update0 = {
    sql"""UPDATE `packages` SET currentVersion=${newVersion.str} WHERE anityaId=${anityaId.toInt}"""
      .updateWithLogHandler(doobieLogHandler)
  }

  def updatePackageVersion(anityaId: AnityaId, newVersion: PackageVersion): ConnectionIO[Int] = {
    updatePackageVersionQuery(anityaId, newVersion).run
  }

  private def retrieveUnusedEmailAddresses: ConnectionIO[List[(EmailId, EmailAddress)]] = {
    sql"""SELECT emails.id, emails.emailAddress FROM emails
         |LEFT JOIN subscriptions ON subscriptions.emailId = emails.id
         |WHERE subscriptions.id IS NULL""".stripMargin
      .queryWithLogHandler[(Int, String)](doobieLogHandler)
      // We use unsafeFromString, because we are sure that emails in the database are valid email addresses
      .map{case (emailId, emailAddress) => (EmailId(emailId), EmailAddress.unsafeFromString(emailAddress))}
      .to[List]
  }

  private def deleteEmailAddress(emailId: EmailId): ConnectionIO[Int] = {
    sql"""DELETE FROM emails WHERE id = ${emailId.toInt}"""
      .updateWithLogHandler(doobieLogHandler)
      .run
  }

  def deleteUnusedEmailAddresses: ConnectionIO[List[(EmailId, EmailAddress)]] = {
    for {
      unusedEmailIdsAndEmailAddresses <- retrieveUnusedEmailAddresses
      _ <- unusedEmailIdsAndEmailAddresses
        .map{_._1}
        .traverse{ deleteEmailAddress }
    } yield unusedEmailIdsAndEmailAddresses
  }

  def upsertPackage(packageName: String, homepage: String, anityaId: Int, version: PackageVersion): ConnectionIO[Int] = {
    for {
      listOfIdNameHomepageAnityaId <- retrievePackageByAnityaIdQuery(anityaId).to[List]
      firstElemOpt = listOfIdNameHomepageAnityaId.headOption
      result <- firstElemOpt match {
        case Some((currentId, _, _, _, _)) =>
          updatePackageQuery(currentId, packageName, homepage, anityaId, version).run
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

  def insertIntoEmailTableIfNotExist(email: EmailAddress): ConnectionIO[EmailId] = {
    for {
      _ <- sql"""INSERT OR IGNORE INTO `emails` (emailAddress) VALUES (${email.str})"""
        .updateWithLogHandler(doobieLogHandler)
        .run
      emailAddressId <- sql"""SELECT `id` FROM `emails` WHERE emailAddress = ${email.str}"""
        .queryWithLogHandler[Int](doobieLogHandler)
        .to[List]
        .map{
          _.headOption.getOrElse{
            throw new Exception(
              s"This is a programming bug! Because we just inserted an email in " +
                s"this transaction, it must be non-empty!"
            )
          }
        }
    } yield EmailId(emailAddressId)
  }

  def insertIntoSubscriptionsIfNotExist(
    emailId: EmailId,
    pkg: FullPackage,
    currentTime: Instant,
    confirmationCode: ConfirmationCode
  ): ConnectionIO[SubscriptionId] = {

    for {
      _ <- sql"""INSERT OR IGNORE INTO `subscriptions` (emailId, packageId, createdTime, confirmationCode) VALUES
         |(${emailId.toInt}, ${pkg.packageId}, ${currentTime.getEpochSecond}, ${confirmationCode.str}) """.stripMargin
        .updateWithLogHandler(doobieLogHandler)
        .run
      subscriptionId <- sql"""SELECT `id` FROM `subscriptions` WHERE emailId = ${emailId.toInt} AND packageId=${pkg.packageId}"""
        .queryWithLogHandler[Int](doobieLogHandler)
        .to[List]
        .map{
        _.headOption
          .getOrElse(throw new Exception(
            s"This is a programming bug! Because we just inserted the subscription " +
              s"in this transaction (or verified that this subscription already exists!)"
          ))
      }
    } yield SubscriptionId(subscriptionId)
  }

  def packageIdsInSubscriptionWithEmail(email: EmailId): doobie.ConnectionIO[List[(SubscriptionId, FullPackage, UnsubscribeCode, ConfirmationCode, Option[Instant])]] = {
    sql"""SELECT subscriptions.id, packages.id, packages.name, packages.homepage, packages.anityaId, packages.currentVersion, unsubscribeCodes.code, subscriptions.confirmationCode, subscriptions.confirmedTime
         |FROM `subscriptions`
         |INNER JOIN `packages` ON packages.id = subscriptions.packageId
         |INNER JOIN `unsubscribeCodes` ON unsubscribeCodes.subscriptionId = subscriptions.id
         |WHERE emailId=${email.toInt}"""
      .stripMargin
      .queryWithLogHandler[(Int, Int, String, String, Int, String, String, String, Option[Long])](doobieLogHandler)
      .map{case (subId, pkgId, pkgName, pkgHomepage, pkgAnityaId, pkgCurrentVersion, unsubscribeCode, confirmationCode, confirmedTimeOpt) =>
        (
          SubscriptionId(subId),
          FullPackage(
            name = PackageName(pkgName),
            homepage = pkgHomepage,
            anityaId = pkgAnityaId,
            packageId = pkgId,
            currentVersion = PackageVersion(pkgCurrentVersion)
          ),
          // We can use this fromString because we're getting it directly from the DB
          UnsubscribeCode.unsafeFromString(unsubscribeCode),
          // Ditto
          ConfirmationCode.unsafeFromString(confirmationCode),
          confirmedTimeOpt.map(Instant.ofEpochSecond)
        )
      }
      .to[List]
  }

  def subscribeToPackagesFullName(
    email: EmailAddress,
    pkgsWithUnsubscribeCodes: NonEmptyList[(FullPackage, UnsubscribeCode)],
    currentTime: Instant,
    confirmationCode: ConfirmationCode
  ): ConnectionIO[Ior[NonEmptyList[SubscriptionAlreadyExists], Int]] = {
    val pkgs = pkgsWithUnsubscribeCodes.map(_._1)
    for {
      emailId <- insertIntoEmailTableIfNotExist(email)
      existingSubscriptions <- packageIdsInSubscriptionWithEmail(emailId)
      pkgIdToExistingSubscriptions = existingSubscriptions.groupBy(_._2.packageId).view.mapValues(_.head).toMap
      insertedOrErr <- pkgs.traverse{ pkg =>
        pkgIdToExistingSubscriptions.get(pkg.packageId) match {
          case Some((subId, fullPackage, unsubscribeCode, existingConfirmationCode, confirmedTimeOpt)) =>
            Either.left[SubscriptionAlreadyExists, (FullPackage, SubscriptionId)](
              SubscriptionAlreadyExists(subId, fullPackage, email, unsubscribeCode, existingConfirmationCode, confirmedTimeOpt)
            ).pure[ConnectionIO]
          case None =>
            insertIntoSubscriptionsIfNotExist(emailId, pkg, currentTime, confirmationCode)
              .map(subId => Either.right[SubscriptionAlreadyExists, (FullPackage, SubscriptionId)]((pkg, subId)))
        }
      }
      _ = logger.debug(
        s"Email address ${email.str} subscribed to these packages with unsubscribe " +
          s"codes: $pkgsWithUnsubscribeCodes resulting in $insertedOrErr"
      )
      insertedPkgs = insertedOrErr.collect{case Right(pkgAndSubId) => pkgAndSubId}
      insertedPkgsMap = insertedPkgs.groupByNel(_._1).view.mapValues(_.head._2).toMap
      insertedPkgsWithUnsubscribeCodes = pkgsWithUnsubscribeCodes
        .map{
          case (pkg, unsubscribeCode) => insertedPkgsMap.get(pkg).map((unsubscribeCode, _))
        }
        .collect{case Some(x) => x}
      _ <- insertedPkgsWithUnsubscribeCodes
        .traverse{
          case (unsubscribeCode, subscriptionId) =>
            createUnsubscribeCodeQuery(unsubscribeCode, subscriptionId).run
        }
    } yield {
      val errors = insertedOrErr.collect{case Left(err) => err}
      val successes = insertedOrErr.collect{case Right(pkg) => pkg}
      NonEmptyList.fromList(errors) match {
        case None => Ior.right(successes.length)
        case Some(nonEmptyErrors) => if (successes.isEmpty) {
          Ior.left(nonEmptyErrors)
        } else {
          Ior.both(nonEmptyErrors, successes.length)
        }
      }
    }
  }

  def changeEmail(oldEmail: EmailAddress, newEmail: EmailAddress): ConnectionIO[Int] = {
    sql"""UPDATE `emails` SET emailAddress = ${newEmail.str} WHERE emailAddress = ${oldEmail.str}"""
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
      .stripMargin
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

  val retrieveAllEmailsSubscribedToAllCIO: ConnectionIO[List[EmailAddress]] =
    sql"""SELECT emailAddress FROM subscriptions
         |INNER JOIN emails ON emails.id = subscriptions.emailId
         |WHERE specialType='ALL' AND subscriptions.confirmedTime IS NOT NULL"""
      .stripMargin
      .query[String]
      // We assume if the email address is in the database it's a valid string
      .map(EmailAddress.unsafeFromString)
      .to[List]

  def retrieveAllEmailsSubscribedToAllFullIO(transactor: Transactor[IO])(implicit contextShift: ContextShift[IO]): IO[List[EmailAddress]] =
    retrieveAllEmailsSubscribedToAllCIO.transact(transactor)


  def retrieveAllEmailsWithAnityaId(anityaId: Int): ConnectionIO[List[EmailAddress]] =
    sql"""SELECT emailAddress FROM subscriptions
         |INNER JOIN emails ON emails.id = subscriptions.emailId
         |INNER JOIN packages ON packages.id = subscriptions.packageId
         |WHERE packages.anityaId=$anityaId AND subscriptions.confirmedTime IS NOT NULL"""
      .stripMargin
      .query[String]
      // We assume if the email address is in the database it's a valid string
      .map(EmailAddress.unsafeFromString)
      .to[List]

  def retrieveAllEmailsWithAnityaIdIO(
    transactor: Transactor[IO],
    anityaId: Int)(
    implicit contextShift: ContextShift[IO]
  ): IO[List[EmailAddress]] =
    retrieveAllEmailsWithAnityaId(anityaId).transact(transactor)

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
