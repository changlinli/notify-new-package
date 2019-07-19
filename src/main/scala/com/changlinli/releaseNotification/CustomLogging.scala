package com.changlinli.releaseNotification

import doobie.util.log.{ExecFailure, LogHandler, ProcessingFailure, Success}
import grizzled.slf4j.Logging

trait CustomLogging extends Logging {
  val doobieLogHandler: LogHandler = LogHandler{
    // Copy pasted from doobie.util.LogHandler
    case Success(s, a, e1, e2) =>
      logger.debug(s"""Successful Statement Execution:
                        |
            |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                        |
            | arguments = [${a.mkString(", ")}]
                        |   elapsed = ${e1.toMillis.toString} ms exec + ${e2.toMillis.toString} ms processing (${(e1 + e2).toMillis.toString} ms total)
          """.stripMargin)

    case ProcessingFailure(s, a, e1, e2, t) =>
      logger.warn(s"""Failed Resultset Processing:
                          |
            |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                          |
            | arguments = [${a.mkString(", ")}]
                          |   elapsed = ${e1.toMillis.toString} ms exec + ${e2.toMillis.toString} ms processing (failed) (${(e1 + e2).toMillis.toString} ms total)
                          |   failure = ${t.getMessage}
          """.stripMargin)

    case ExecFailure(s, a, e1, t) =>
      logger.warn(s"""Failed Statement Execution:
                          |
            |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                          |
            | arguments = [${a.mkString(", ")}]
                          |   elapsed = ${e1.toMillis.toString} ms exec (failed)
                          |   failure = ${t.getMessage}
          """.stripMargin)
  }
}
