package com.changlin.releaseNotification

import java.util.concurrent.Executors

import cats.data.NonEmptyList
import cats.effect.{Blocker, ContextShift, IO}
import com.changlinli.releaseNotification.Persistence
import com.changlinli.releaseNotification.WebServer.EmailAddress
import com.changlinli.releaseNotification.data.{FullPackage, PackageName, UnsubscribeCode}
import com.changlinli.releaseNotification.ids.SubscriptionId
import doobie.implicits._
import doobie.scalatest.IOChecker
import org.scalatest._
import org.sqlite.SQLiteException

import scala.concurrent.ExecutionContext

class TestPersistence extends FlatSpec with Matchers with IOChecker {
  private val threadPool = Executors.newFixedThreadPool(4)
  private val executionContext = ExecutionContext.fromExecutor(threadPool)
  private val connectionExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
  private val blocker: Blocker = Blocker.liftExecutionContext(
    scala.concurrent.ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool
    )
  )

  implicit val contextSwitch: ContextShift[IO] = IO.contextShift(executionContext)

  "Inserting a subscription to a non-existent package" should "blow up with a failure" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        )
      )
    } yield ()
    an [SQLiteException] should be thrownBy action.transact(transactor).unsafeRunSync()
  }
  it should "succeed if the package exists" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1)
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        )
      )
    } yield ()
    action.transact(transactor).unsafeRunSync()
  }

  "The persistence layer" should "succeed in retrieving all email addresses subscribed to all packages and return an empty list of them" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      result <- Persistence.retrieveAllEmailsSubscribedToAllA
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List.empty)
  }
  it should "succeed in retrieving all email addresses subscribed to a to a dummy package" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1)
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        )
      )
      result <- Persistence.retrieveAllEmailsWithAnityaIdA(1)
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List(EmailAddress("hello@hello.com")))
  }
  it should "succeed in retrieving a package from the suffix of its name" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1)
      result <- Persistence.searchForPackagesByNameFragment("ello")
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List(FullPackage(name = PackageName("hello"), homepage = "hello.com", packageId = 1, anityaId = 1)))
  }
  it should "succeed in using an unsubscribe code for an existing unsubscribe code" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1)
      _ <- Persistence.subscribeToPackagesFullName(
        EmailAddress("hello@hello.com"),
        NonEmptyList.of(
          (
            FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1),
            UnsubscribeCode.unsafeFromString("unsubscribeString")
          )
        )
      )
      unsubscribeCodeOpt <- Persistence.getUnsubscribeCodeForSubscription(SubscriptionId(1))
    } yield unsubscribeCodeOpt
    action.transact(transactor).unsafeRunSync() should be (Some(UnsubscribeCode.unsafeFromString("unsubscribeString")))
  }

  override def transactor: doobie.Transactor[IO] = Persistence.createTransactorRaw(":memory:", connectionExecutionContext, blocker)
}
