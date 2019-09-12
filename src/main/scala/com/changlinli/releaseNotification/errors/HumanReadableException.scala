package com.changlinli.releaseNotification.errors

trait HumanReadableException extends Exception {
  val humanReadableMessage: String

  override def getMessage: String = humanReadableMessage
}

