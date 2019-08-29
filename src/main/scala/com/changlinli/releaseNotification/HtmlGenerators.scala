package com.changlinli.releaseNotification

import cats.data.NonEmptyList
import com.changlinli.releaseNotification.WebServer.{NoPackagesFoundForAnityaIds, RequestProcessError, SubscriptionsAlreadyExistErr}
import com.changlinli.releaseNotification.data.{ConfirmationCode, FullPackage, UnsubscribeCode}
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
        formatAllPackages(packages.toList),
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
        formatAllPackages(packages.toList)
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
        s"Anitya ID: ${pkg.anityaId}"
      ),
      li(
        s"Current Version: ${pkg.currentVersion.str}"
      )
    )
  }

  private def formatAllPackages(pkgs: List[FullPackage]): Text.TypedTag[String] = {
    val listItems = pkgs.map(
      pkg => li(formatSinglePackage(pkg))
    )
    ul(listItems: _*)
  }

  def submittedFormWithSomeErrors(packages: NonEmptyList[FullPackage], error: RequestProcessError): Text.TypedTag[String] = {
    error match {
      case NoPackagesFoundForAnityaIds(anityaIds) =>
        insertIntoBody(
          div(
            p("We encountered some errors when trying to process your request, but we've nonetheless successfully submitted requests to subscribe to some of the packages you asked for."),
            p(
              s"You seem to have asked for certain packages that didn't exist. Their Anitya IDs are the following: ${anityaIds.map(_.toInt).toList.mkString(",")}"
            ),
            p(
              s"Here are the packages whose requests for subscription we successfully processed:"
            ),
            formatAllPackages(packages.toList)
          )
        )
      case SubscriptionsAlreadyExistErr(_) =>
        // We're not going to reveal information that an email address is already subscribed to certain packages
        successfullySubmittedFrom(packages)
    }
  }

  def successfullySubmittedFrom(packages: NonEmptyList[FullPackage]): Text.TypedTag[String] = {
    insertIntoBody(
      div(
        "You've submitted a request to subscribe to the following packages:",
        formatAllPackages(packages.toList)
      )
    )
  }

}
