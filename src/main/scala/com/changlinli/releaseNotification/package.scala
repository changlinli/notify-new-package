package com.changlinli

package object releaseNotification {
  implicit class StdOps[A](x: A) {
    def |>[B](f: A => B): B = f(x)
  }

}
