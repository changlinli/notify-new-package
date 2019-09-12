package com.changlinli.releaseNotification.data

import cats.Order
import com.changlinli.releaseNotification.data

/**
  * This class is mainly for use with [[com.changlinli.releaseNotification.HtmlGenerators]]
  *
  * We want to ensure that we don't leak information about which packages an
  * email address has subscribed to on the web page (which any anonymous user
  * can access). This means we accumulate both packages an email address is
  * already subscribed to and packages an email address has not yet subscribed
  * to together in some circumstances so a web user cannot distinguish between
  * the two.
  *
  * To that end we make sure that no information in the ordering of packages is
  * leaked to prevent an anonymous user from finding out.
  *
  * Note that this is not sufficient to prevent timing attacks, but we'll cross
  * that bridge if the need arises.
  */
abstract sealed case class SanitizedList[+A](toList: List[A])

object SanitizedList {
  def fromList[A : Order](xs: List[A]): SanitizedList[A] =
    new data.SanitizedList(xs.sorted(implicitly[Order[A]].toOrdering)) {}
}
