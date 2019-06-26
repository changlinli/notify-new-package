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
        val mySaslConfig = new SaslConfig {
          override def getSaslMechanism(mechanisms: Array[String]): SaslMechanism = {
            println(s"Input: ${mechanisms.toList}")
            val result = saslConfig.getSaslMechanism(mechanisms)
            println(s"Output: $result")
            new SaslMechanism {
              override def getName: String = result.getName

              override def handleChallenge(challenge: LongString, username: String, password: String): LongString = {
                println(s"Handle input: $challenge, $username, $password")
                val innerResult = result.handleChallenge(challenge, username, password)
                println(s"Handle output: $innerResult")
                innerResult
              }
            }
          }
        }
        factory.setSaslConfig(mySaslConfig)
        val amqpClient = new AmqpClientEffect[F]
        val conn       = new ConnectionEffect[F](factory, addresses)
        val internalQ  = new LiveInternalQueue[F](config.internalQueueSize.getOrElse(500))
        val acker      = new AckingProgram[F](config, amqpClient)
        val consumer   = new ConsumingProgram[F](amqpClient, internalQ)
        new Fs2Rabbit[F](conn, amqpClient, acker, consumer)
    }
  }
}
