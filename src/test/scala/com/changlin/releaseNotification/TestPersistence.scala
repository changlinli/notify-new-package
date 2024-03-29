package com.changlin.releaseNotification

import java.time.Instant
import java.util.concurrent.Executors

import cats.data.{Ior, NonEmptyList}
import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import com.changlinli.releaseNotification.{DependencyUpdate, Persistence}
import com.changlinli.releaseNotification.data.{ConfirmationCode, EmailAddress, FullPackage, PackageName, PackageVersion, UnsubscribeCode}
import com.changlinli.releaseNotification.errors.SubscriptionAlreadyExists
import com.changlinli.releaseNotification.ids.SubscriptionId
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import org.scalatest._
import org.sqlite.SQLiteException

import scala.concurrent.ExecutionContext

class TestPersistence extends FlatSpec with Matchers with BeforeAndAfterAll {
  private val threadPool = Executors.newFixedThreadPool(4)
  private val executionContext = ExecutionContext.fromExecutor(threadPool)
  private val connectionThreadPool = Executors.newFixedThreadPool(4)
  private val connectionExecutionContext = ExecutionContext.fromExecutor(connectionThreadPool)
  private val blockerThreadPool = Executors.newCachedThreadPool
  private val blocker: Blocker = Blocker.liftExecutionContext(
    scala.concurrent.ExecutionContext.fromExecutorService(
      blockerThreadPool
    )
  )

  implicit val contextSwitch: ContextShift[IO] = IO.contextShift(executionContext)

  val transactor: doobie.Transactor[IO] = Persistence.createTransactorRaw(":memory:", connectionExecutionContext, blocker)

  override def afterAll(): Unit = {
    threadPool.shutdown()
    connectionThreadPool.shutdown()
    blockerThreadPool.shutdown()
  }


  "Inserting a subscription" should "blow up with a failure if the package does not exist" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress.unsafeFromString("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0")),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
    } yield ()
    an [SQLiteException] should be thrownBy action.transact(transactor).unsafeRunSync()
  }
  it should "succeed if the package exists" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1, PackageVersion("1.0"))
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress.unsafeFromString("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0")),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
    } yield ()
    action.transact(transactor).unsafeRunSync()
  }
  it should "yield an error if the subscription already exists" in {
    val fullPackage = FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0"))
    val email = EmailAddress.unsafeFromString("hello@hello.com")
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1, PackageVersion("1.0"))
      _ <- Persistence.subscribeToPackagesFullName(
        email,
        NonEmptyList.of(
          (
            fullPackage,
            UnsubscribeCode.unsafeFromString("unsubscribeString0")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
      secondResult <- Persistence.subscribeToPackagesFullName(
        email,
        NonEmptyList.of(
          (
            fullPackage,
            UnsubscribeCode.unsafeFromString("unsubscribeString1")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
    } yield secondResult
    val expectedResult = Ior.Left(
      NonEmptyList.of(
        SubscriptionAlreadyExists(
          subscriptionId = SubscriptionId(1),
          pkg = fullPackage,
          emailAddress = email,
          packageUnsubscribeCode = UnsubscribeCode.unsafeFromString("unsubscribeString0"),
          confirmationCode = ConfirmationCode.unsafeFromString("confirm"),
          confirmedTime = None
        )
      )
    )
    action.transact(transactor).unsafeRunSync() should be (expectedResult)
  }

  "The persistence layer" should "succeed in retrieving all email addresses subscribed to all packages and return an empty list of them" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      result <- Persistence.retrieveAllEmailsSubscribedToAllCIO
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List.empty)
  }
  it should "succeed in retrieving all email addresses subscribed to a dummy package" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1, PackageVersion("1.0"))
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress.unsafeFromString("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0")),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
      _ <- Persistence.confirmSubscription(ConfirmationCode.unsafeFromString("confirm"), Instant.EPOCH)
      result <- Persistence.retrieveAllEmailsWithAnityaId(1)
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List(EmailAddress.unsafeFromString("hello@hello.com")))
  }
  it should "succeed in retrieving a package from the suffix of its name" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1, PackageVersion("1.0"))
      result <- Persistence.searchForPackagesByNameFragment("ello")
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List(
      FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0")),
    ))
  }
  it should "succeed in using an unsubscribe code for an existing unsubscribe code" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1, PackageVersion("1.0"))
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress.unsafeFromString("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0")),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
      unsubscribeCodeOpt <- Persistence.getUnsubscribeCodeForSubscription(SubscriptionId(1))
    } yield unsubscribeCodeOpt
    action.transact(transactor).unsafeRunSync() should be (Some(UnsubscribeCode.unsafeFromString("unsubscribeString")))
  }
  it should "succeed in creating a new package when a DependencyUpdate comes in for a non-existent package" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage(
        packageName = "hello",
        version = PackageVersion("0.01"),
        homepage = "example.com",
        anityaId = 1
      )
      results <- Persistence.retrievePackageByAnityaIdQuery(1).to[List]
    } yield results
    action.transact(transactor).unsafeRunSync().length should be (1)
  }
  it should "not create a new package when an upsert comes in for a pre-existing package" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage(
        "hello",
        "hello.com",
        1,
        PackageVersion("1.0")
      )
      _ <- Persistence.upsertPackage(
          packageName = "hello",
          version = PackageVersion("2.0"),
          homepage = "example.com",
          anityaId = 1
      )
      results <- Persistence.retrievePackageByAnityaIdQuery(1).to[List]
    } yield results
    action.transact(transactor).unsafeRunSync().map{case (_, _, _, anityaId, currentVersion) => (anityaId, currentVersion)} should be (List((1, "2.0")))
  }
  it should "delete unused emails if an email is no longer subscribed to any packages" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1, PackageVersion("1.0"))
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress.unsafeFromString("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1, currentVersion = PackageVersion("1.0")),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        ),
        Instant.EPOCH,
        ConfirmationCode.unsafeFromString("confirm")
      )
      deletedEmailAddresses <- Persistence.deleteUnusedEmailAddresses
      _ <- (deletedEmailAddresses.length shouldBe 0).pure[ConnectionIO]
      _ <- Persistence.unsubscribeUsingCode(UnsubscribeCode.unsafeFromString("unsubscribeString"))
      results <- Persistence.deleteUnusedEmailAddresses
    } yield {
      results.map(_._2)
    }
    action
      .transact(transactor)
      .unsafeRunSync()
      .shouldBe(List(EmailAddress.unsafeFromString("hello@hello.com")))
  }

}
