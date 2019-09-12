package com.changlinli.releaseNotification.errors

import java.time.Instant

import com.changlinli.releaseNotification.data.{ConfirmationCode, EmailAddress, FullPackage, UnsubscribeCode}
import com.changlinli.releaseNotification.ids.{AnityaId, SubscriptionId}

sealed trait RequestProcessError extends HumanReadableException with Product with Serializable {
  val humanReadableMessage: String

  override def getMessage: String = humanReadableMessage
}
final case class SubscriptionAlreadyExists(
  subscriptionId: SubscriptionId,
  pkg: FullPackage,
  emailAddress: EmailAddress,
  packageUnsubscribeCode: UnsubscribeCode,
  confirmationCode: ConfirmationCode,
  confirmedTime: Option[Instant]
) extends RequestProcessError {
  override val humanReadableMessage: String =
    s"A subscription already exists for the following package ID, package name, subscription ID, and email addresses: " +
      s"Package ID: ${subscriptionId.toInt}, Package name: ${pkg.name}, Subscription ID: ${subscriptionId.toInt}, Email: ${emailAddress.str}"
}
final case class NoPackagesFoundForAnityaId(anityaId: AnityaId) extends RequestProcessError {
  override val humanReadableMessage: String =
    s"We were unable to find any packages corresponding to the following anitya ID: ${anityaId.toInt}"
}

object RequestProcessError {
  def splitErrors(errors: List[RequestProcessError]): (List[SubscriptionAlreadyExists], List[NoPackagesFoundForAnityaId]) = {
    errors.foldLeft((List.empty[SubscriptionAlreadyExists], List.empty[NoPackagesFoundForAnityaId])){
      case ((subscriptionAlreadyExistsErrs, noPackagesFoundForAnityaIdErrs), newErr) =>
        newErr match {
          case subscriptionAlreadyExistsErr: SubscriptionAlreadyExists =>
            (subscriptionAlreadyExistsErr :: subscriptionAlreadyExistsErrs, noPackagesFoundForAnityaIdErrs)
          case noPackagesFoundErr: NoPackagesFoundForAnityaId =>
            (subscriptionAlreadyExistsErrs, noPackagesFoundErr :: noPackagesFoundForAnityaIdErrs)
        }
    }
  }

}

