package dev.profunktor.fs2rabbit

import cats.effect.ConcurrentEffect
import cats.syntax.functor._
import com.rabbitmq.client.{DefaultSaslConfig, LongString, SaslConfig, SaslMechanism}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.interpreter.{AmqpClientEffect, ConnectionEffect, Fs2Rabbit, LiveInternalQueue}
import dev.profunktor.fs2rabbit.program.{AckingProgram, ConsumingProgram}
import javax.net.ssl.SSLContext

object Blah {

  def customCreateFs2Rabbit[F[_] : ConcurrentEffect](config: Fs2RabbitConfig, sslContext: Option[SSLContext]) = {
    ConnectionEffect.mkConnectionFactory[F](config, sslContext).map {
      case (factory, addresses) =>
        val saslConfig = DefaultSaslConfig.EXTERNAL
        factory.setSaslConfig(saslConfig)
        val amqpClient = new AmqpClientEffect[F]
        val conn       = new ConnectionEffect[F](factory, addresses)
        val internalQ  = new LiveInternalQueue[F](config.internalQueueSize.getOrElse(500))
        val acker      = new AckingProgram[F](config, amqpClient)
        val consumer   = new ConsumingProgram[F](amqpClient, internalQ)
        new Fs2Rabbit[F](conn, amqpClient, acker, consumer)
    }
  }
}
