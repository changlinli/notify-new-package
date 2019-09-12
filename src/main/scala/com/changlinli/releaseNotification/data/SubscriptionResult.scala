package com.changlinli.releaseNotification.data

import cats.data.NonEmptyList

final case class SuccessfulSubscriptionResult(
  successfullySubscribedPackages: NonEmptyList[SuccessfulSinglePackageSubscription],
  confirmationCode: ConfirmationCode
)

final case class SuccessfulSinglePackageSubscription(
  pkg: FullPackage,
  unsubscribeCode: UnsubscribeCode
)
