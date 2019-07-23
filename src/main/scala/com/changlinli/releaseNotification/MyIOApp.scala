package com.changlinli.releaseNotification

import java.util.concurrent.ExecutorService

import cats.effect.{Blocker, ContextShift, ExitCode, IO, Timer}

import scala.concurrent.ExecutionContext

trait MyIOApp {
  final val mainExecutorService: ExecutorService = ThreadPools.mainExecutorService

  final val mainExecutionContext: ExecutionContext = ThreadPools.mainExecutionContext

  final val blockingExecutorService: ExecutorService = ThreadPools.blockingExecutorService

  final val blockingExecutionContext: ExecutionContext = ThreadPools.blockingExecutionContext

  final val blocker: Blocker = ThreadPools.blocker

  final implicit def contextShift: ContextShift[IO] = ThreadPools.contextShift

  final implicit def timer: Timer[IO] = ThreadPools.timer

  def run(args: List[String]): IO[ExitCode]

  final def main(args: Array[String]): Unit = {
    val exitCode = run(args.toList).unsafeRunSync()
    System.exit(exitCode.code)
  }

}
