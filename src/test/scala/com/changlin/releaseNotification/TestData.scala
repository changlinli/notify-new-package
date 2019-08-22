package com.changlin.releaseNotification

import com.changlinli.releaseNotification.data.EmailAddress
import org.scalatest.{FlatSpec, Matchers}

class TestData  extends FlatSpec with Matchers {
  "EmailAddress" should "accept a valid email address" in {
    EmailAddress.fromString("hello@hello.com") should be (Some(EmailAddress.unsafeFromString("hello@hello.com")))
  }
  it should "reject an invalid email address" in {
    EmailAddress.fromString("blah @hello.com") should be (None)
  }

}
