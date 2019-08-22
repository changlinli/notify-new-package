package com.changlinli.releaseNotification

import dev.profunktor.fs2rabbit.model.RoutingKey
import io.circe.{DecodingFailure, Json}

sealed trait AppError extends Exception
final case class PayloadParseFailure(decodingFailure: DecodingFailure, jsonAttempted: Json) extends AppError
final case class IncorrectRoutingKey(routingKeyObserved: RoutingKey) extends AppError

