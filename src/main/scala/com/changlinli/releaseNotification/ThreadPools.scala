package com.changlinli.releaseNotification

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{Blocker, ContextShift, ExitCode, IO, Timer}

import scala.concurrent.ExecutionContext

object ThreadPools {
  val mainExecutorService: ExecutorService =
    Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors())

  val mainExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(mainExecutorService)

  val blockingExecutorService: ExecutorService =
    Executors.newCachedThreadPool()

  val blockingExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(blockingExecutorService)

  val blocker: Blocker =
    Blocker.liftExecutionContext(blockingExecutionContext)

  implicit def contextShift: ContextShift[IO] =
    IO.contextShift(mainExecutionContext)

  implicit def timer: Timer[IO] = IO.timer(mainExecutionContext)

}
