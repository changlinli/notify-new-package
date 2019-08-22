package com.changlinli.releaseNotification.data

sealed abstract case class EmailAddress (str: String)

object EmailAddress {
  // This regex comes from https://github.com/hmrc/emailaddress/blob/1db535ab82cbdd73adfe2d4c17b88e29f4b1d891/src/main/scala/uk/gov/hmrc/emailaddress/EmailAddress.scala
  // Apache License for it is recreated here:
  /*
   * Copyright 2019 HM Revenue & Customs
   *
   * Licensed under the Apache License, Version 2.0 (the "License");
   * you may not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *     http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS,
   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   * See the License for the specific language governing permissions and
   * limitations under the License.
   */
  private val validEmail = """^([a-zA-Z0-9.!#$%&’'*+/=?^_`{|}~-]+)@([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*)$""".r

  private def isValid(email: String): Boolean = email match {
    case validEmail(_,_) => true
    case _ => false
  }

  def unsafeFromString(str: String): EmailAddress = fromString(str).getOrElse{
    throw new Exception(s"You provided a string as a candidate for an email address ($str) that does not appear to be a valid email address!")
  }

  def fromString(str: String): Option[EmailAddress] = {
    if (isValid(str)) {
      Some(new EmailAddress(str) {})
    } else {
      None
    }
  }
}
