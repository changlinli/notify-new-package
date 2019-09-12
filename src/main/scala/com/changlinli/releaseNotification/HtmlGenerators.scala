package com.changlinli.releaseNotification

import cats.data.NonEmptyList
import com.changlinli.releaseNotification.data.{ConfirmationCode, FullPackage, SanitizedList, UnsubscribeCode}
import com.changlinli.releaseNotification.errors._
import scalatags.Text
import scalatags.Text.all._

object HtmlGenerators {

  private def insertIntoBody(bodyHtml: Text.TypedTag[String]*): Text.TypedTag[String] = {
    html(
      head(
        meta(charset := "utf-8"),
        link(rel := "stylesheet", tpe := "text/css", href := "style.css")
      ),
      body(
        bodyHtml: _*
      )
    )
  }

  def queryUserAboutSubscribeConfirmation(packages: NonEmptyList[FullPackage], confirmationCode: ConfirmationCode): Text.TypedTag[String] = {
    insertIntoBody(
      div(
        "Are you sure you want to subscribe to version updates concerning the following packages? If not feel, feel free to navigate to any other page.",
        formatAllPackages(SanitizedList.fromList(packages.toList)),
        form(
          action := s"/${WebServer.confirmationPath}/${confirmationCode.str}",
          method := "post",
          input(
            tpe := "submit",
            value := "Yes I want to subscribe!"
          )
        )
      )
    )
  }

  def subscribeConfirmation(packages: NonEmptyList[FullPackage]): Text.TypedTag[String] = {
    insertIntoBody(
      div(
        "You've successfully subscribed to version updates concerning the following packages (you'll be getting an email about this too).",
        formatAllPackages(SanitizedList.fromList(packages.toList))
      )
    )
  }

  def unsubscribePage(
    packageToBeUnsubscribedFrom: FullPackage,
    unsubscribeCode: UnsubscribeCode
  ): Text.TypedTag[String] = {
    insertIntoBody(
      div(
        "Are you sure you want to unsubscribe to the following package? If not feel free to navigate to any other page.",
        formatSinglePackage(packageToBeUnsubscribedFrom)
      ),
      form(
        action := s"/${WebServer.unsubscribePath}/${unsubscribeCode.str}",
        method := "post",
        input(
          tpe := "submit",
          value := "Yes I want to unsubscribe!"
        )
      )
    )
  }

  def unsubcribeConfirmation(packageUnsubscribedFrom: FullPackage): Text.TypedTag[String] = {
    insertIntoBody(
      div(
        "You've successfully unsubscribed from the following package (you'll be getting an email about this too).",
        formatSinglePackage(packageUnsubscribedFrom)
      )
    )
  }

  private def formatSinglePackage(pkg: FullPackage): Text.TypedTag[String] = {
    ul(
      li(
        s"Package name: ${pkg.name.str}"
      ),
      li(
        s"Package homepage: ${pkg.homepage}"
      ),
      li(
        s"Anitya ID: https://release-monitoring.org/project/${pkg.anityaId}"
      ),
      li(
        s"Current Version: ${pkg.currentVersion.str}"
      )
    )
  }

  private def formatAllPackages(pkgs: SanitizedList[FullPackage]): Text.TypedTag[String] = {
    val listItems = pkgs.toList.map(
      pkg => li(formatSinglePackage(pkg))
    )
    ul(listItems: _*)
  }

  def submittedFormWithSomeErrors(packages: NonEmptyList[FullPackage], errors: NonEmptyList[RequestProcessError]): Text.TypedTag[String] = {
    // If a new member is added to the tuple here, you must add a new else if clause below!
    val (subscriptionAlreadyExists, noPackagesFoundForAnityaId) = RequestProcessError.splitErrors(errors.toList)
    val allPackages = packages ++ subscriptionAlreadyExists.map(_.pkg)
    if (noPackagesFoundForAnityaId.nonEmpty) {
      insertIntoBody(
        div(
          p("We encountered some errors when trying to process your request, but we've nonetheless successfully submitted requests to subscribe to some of the packages you asked for."),
          p(
            s"You seem to have asked for certain packages that didn't exist. Their Anitya IDs are the following: ${noPackagesFoundForAnityaId.map(_.anityaId.toInt).mkString(",")}"
          ),
          p(
            s"Here are the packages whose requests for subscription we successfully processed:"
          ),
          // We always want a user to think that all packages have been
          // subscribed to, so we state all packages have been successfully processed.
          formatAllPackages(SanitizedList.fromList(allPackages.toList))
        )
      )
    } else if (subscriptionAlreadyExists.nonEmpty) {
      // We always want a user to think that all packages have been
      // subscribed to, so we state all packages have been successfully processed.
      successfullySubmittedFrom(allPackages)
    } else {
      throw new Exception(
        s"This is a programmer bug! We should have included all possible non-empty " +
          s"error cases in the above else if clauses. In particular, at least one of " +
          s"the else if clauses should trip because we must have a non-empty list of " +
          s"errors (as guaranteed by our input type signature)!"
      )
    }
  }

  def submittedFormWithErrors(errors: NonEmptyList[RequestProcessError]): Text.TypedTag[String] = {
    // If a new member is added to the tuple here, you must add a new else if clause below!
    val (subscriptionAlreadyExists, noPackagesFoundForAnityaId) = RequestProcessError.splitErrors(errors.toList)
    if (noPackagesFoundForAnityaId.nonEmpty) {
      insertIntoBody(
        div(
          p("We encountered some errors when trying to process your request, but we've nonetheless successfully submitted requests to subscribe to some of the packages you asked for."),
          p(
            s"You seem to have asked for certain packages that didn't exist. Their Anitya IDs are the following: ${noPackagesFoundForAnityaId.map(_.anityaId.toInt).mkString(",")}"
          ),
          p(
            s"Here are the packages whose requests for subscription we successfully processed:"
          ),
          // We don't want to leak any information about which
          // packages an email address is subscribed to, except in emails. We
          // always want a user to think that all packages have been
          // subscribed to. So we still list these as successfully processed
          formatAllPackages(SanitizedList.fromList(subscriptionAlreadyExists.map(_.pkg)))
        )
      )
    } else if (subscriptionAlreadyExists.nonEmpty) {
      // We know this is safe because of the if check
      val nonEmptySubscriptionAlreadyExists = NonEmptyList.fromListUnsafe(subscriptionAlreadyExists)
      successfullySubmittedFrom(nonEmptySubscriptionAlreadyExists.map(_.pkg))
    } else {
      throw new Exception(
        s"This is a programmer bug! We should have included all possible non-empty " +
          s"error cases in the above else if clauses. In particular, at least one of " +
          s"the else if clauses should trip because we must have a non-empty list of " +
          s"errors (as guaranteed by our input type signature)!"
      )
    }
  }

  def successfullySubmittedFrom(packages: NonEmptyList[FullPackage]): Text.TypedTag[String] = {
    insertIntoBody(
      div(
        "You've submitted a request to subscribe to the following packages. Please check your email inbox for a confirmation email.",
        formatAllPackages(SanitizedList.fromList(packages.toList))
      )
    )
  }

  def dealWithEmailSubmissionError(error: SubscribeToPackagesError): Text.TypedTag[String] = {
    error match {
      case PackagesKeyNotFound =>
        insertIntoBody(
          div(
            "It looks like you submitted a form without having selected any packages. Please go back and select at least one package."
          )
        )
      case NoPackagesSelected =>
        insertIntoBody(
          div(
            "It looks like you submitted a form without having selected any packages. Please go back and select at least one package."
          )
        )
      case EmailAddressKeyNotFound =>
        insertIntoBody(
          div(
            "Hmmmm... you submitted a form without an email address key. Are you using an automated script to subscribe?" +
              " If so I would highly recommend you directly " +
              "subscribe to Fedora's AMQP broker instead of using this service."
          )
        )
      case AnityaIdFieldNotValidInteger(idStr) =>
        insertIntoBody(
          div(
            s"Hmmmm... you submitted a form using invalid Anitya (release-monitoring.org) " +
              s"IDs (in particular you submitted $idStr, which is not a valid integer). " +
              s"Are you using an automated script to subscribe?" +
              " If so I would highly recommend you directly " +
              "subscribe to Fedora's AMQP broker instead of using this service."
          )
        )
      case EmailAddressIncorrectFormat("") =>
        insertIntoBody(
          div(
            "You left the email address field blank! Please submit a valid email address."
          )
        )
      case EmailAddressIncorrectFormat(candidateEmailStr) =>
        insertIntoBody(
          div(
            s"$candidateEmailStr doesn't seem to be a valid email address. Please submit a valid email address."
          )
        )
    }
  }

}
