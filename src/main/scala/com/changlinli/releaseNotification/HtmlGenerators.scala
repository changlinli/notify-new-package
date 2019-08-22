package com.changlinli.releaseNotification

import com.changlinli.releaseNotification.data.FullPackage
import scalatags.Text
import scalatags.Text.all._

object HtmlGenerators {

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
        s"Current Version: ${pkg.currentVersion}"
      )
    )
  }

  private def formatAllPackages(pkgs: List[FullPackage]) = {
    val listItems = pkgs.map(
      pkg => li(formatSinglePackage(pkg))
    )
    ul(listItems: _*)
  }

  def successfullySubmittedFrom(packages: List[FullPackage]): Text.TypedTag[String] = {
    html(
      head(
        meta(charset := "utf-8"),
        link(rel := "stylesheet", tpe := "text/css", href := "style.css")
      ),
      body(
        div(
          "You've submitted a request to subscribe to the following packages:",
          formatAllPackages(packages)
        )
      )
    )
  }

}
