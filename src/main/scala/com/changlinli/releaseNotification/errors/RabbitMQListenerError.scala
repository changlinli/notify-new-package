package com.changlinli.releaseNotification.errors

import dev.profunktor.fs2rabbit.model.RoutingKey
import io.circe.{DecodingFailure, Json}

sealed trait RabbitMQListenerError extends Exception
final case class PayloadParseFailure(decodingFailure: DecodingFailure, jsonAttempted: Json) extends RabbitMQListenerError
final case class IncorrectRoutingKey(routingKeyObserved: RoutingKey) extends RabbitMQListenerError

