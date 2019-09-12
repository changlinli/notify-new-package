package com.changlin.releaseNotification

import java.time.Instant
import java.util.concurrent.Executors

import cats.data.{Ior, NonEmptyList}
import cats.effect.{Blocker, ContextShift, IO}
import com.changlinli.releaseNotification.Main.DependencyUpdate
import com.changlinli.releaseNotification.{Persistence, WebServer}
import com.changlinli.releaseNotification.data.{ConfirmationCode, EmailAddress, FullPackage, PackageName, PackageVersion, UnsubscribeCode}
import com.changlinli.releaseNotification.errors.SubscriptionAlreadyExists
import com.changlinli.releaseNotification.ids.{AnityaId, SubscriptionId}
import doobie.implicits._
import doobie.scalatest.IOChecker
import org.scalatest._
import org.sqlite.SQLiteException

import scala.concurrent.ExecutionContext

class TestWebServer extends FlatSpec with Matchers with BeforeAndAfterAll {
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

  "WebServer" should "result in two errors if two packages are subscribed to that were already subscribed to" in {
    val subscriptionAction = WebServer.fullySubscribeToPackages(
      EmailAddress.unsafeFromString("me@hello.com"),
      NonEmptyList.of(
        (AnityaId(0), UnsubscribeCode.unsafeFromString("unsubscribe0")),
        (AnityaId(1), UnsubscribeCode.unsafeFromString("unsubscribe1"))
      ),
      Instant.EPOCH,
      ConfirmationCode.unsafeFromString("confirm0")
    )
    val testAction = for {
      _ <- Persistence.initializeDatabase
      _ <- Persistence.upsertPackage("hello0", "hello.com", 0, PackageVersion("1.0"))
      _ <- Persistence.upsertPackage("hello1", "hello.com", 1, PackageVersion("1.0"))
      _ <- subscriptionAction
      resultsOfDuplicateSubscription <- subscriptionAction
    } yield resultsOfDuplicateSubscription
    testAction
      .transact(transactor)
      .unsafeRunSync()
      .leftMap(_.collect{case x: SubscriptionAlreadyExists => x}.map(_.pkg.name).sortBy(_.str))
      .shouldBe(Ior.Left(List(PackageName("hello0"), PackageName("hello1"))))
  }

}
