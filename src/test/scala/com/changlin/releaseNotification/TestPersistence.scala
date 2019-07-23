package com.changlin.releaseNotification

import java.util.concurrent.Executors

import cats.data.NonEmptyList
import cats.effect.{Blocker, Bracket, ContextShift, IO}
import cats.implicits._
import com.changlinli.releaseNotification.Persistence
import com.changlinli.releaseNotification.WebServer.{EmailAddress, FullPackage, PackageName}
import doobie._
import doobie.implicits._
import doobie.scalatest.IOChecker
import doobie.util.{Colors, testing}
import doobie.util.testing.Analyzable
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
      _ <- Persistence.subscribeToPackagesFullName(EmailAddress("hello@hello.com"), NonEmptyList.of(FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1)))
    } yield ()
    an [SQLiteException] should be thrownBy action.transact(transactor).unsafeRunSync()
  }
  it should "succeed if the package exists" in {
    val action = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello", "hello.com", 1)
      _ <- Persistence.subscribeToPackagesFullName(EmailAddress("hello@hello.com"), NonEmptyList.of(FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1)))
    } yield ()
    action.transact(transactor).unsafeRunSync()
  }
  it should "succeed in retrieving all email addresses subscribed to all packages and return an empty list of them" in {
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
      _ <- Persistence.subscribeToPackagesFullName(EmailAddress("hello@hello.com"), NonEmptyList.of(FullPackage(name = PackageName("hello"), homepage = "hello.com", anityaId = 1, packageId = 1)))
      result <- Persistence.retrieveAllEmailsWithAnityaIdA(1)
    } yield result
    action.transact(transactor).unsafeRunSync() should be (List(EmailAddress("hello@hello.com")))
  }

  override def transactor: doobie.Transactor[IO] = Persistence.transactorA(":memory:", connectionExecutionContext, blocker)
}
