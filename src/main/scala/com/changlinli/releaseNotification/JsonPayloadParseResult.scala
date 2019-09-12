package com.changlinli.releaseNotification

import com.changlinli.releaseNotification.data.{PackageName, PackageVersion}
import com.changlinli.releaseNotification.ids.AnityaId
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor, Json}

sealed trait JsonPayloadParseResult

object JsonPayloadParseResult {
  implicit val parseResultDecoder: Decoder[JsonPayloadParseResult] =
    // Ordering matters here because the created decoder will also decode the json meant for the edited decoder
    DependencyUpdate.dependencyUpdateDecoder
      .or(PackageEdited.packageEditedDecoder.map(identity[JsonPayloadParseResult]))
      .or(NewPackageCreated.newPackageCreatedDecoder.map(identity[JsonPayloadParseResult]))
}

final case class PackageEdited(
  packageName: PackageName,
  packageVersion: Option[PackageVersion],
  homepage: String,
  anityaId: AnityaId
) extends JsonPayloadParseResult

object PackageEdited {
  implicit val packageEditedDecoder: Decoder[PackageEdited] = new Decoder[PackageEdited] {
    override def apply(c: HCursor): Result[PackageEdited] = {
      for {
        // We throw away this changes field, because we don't actually use it (
        // we might for a future version). We only use it as a marker for
        // whether this is a JSON payload corresponding to editing a package.
        _ <- c.downField("message").downField("changes").as[Json]
        projectName <- c.downField("project").downField("name").as[PackageName]
        projectVersion <- c.downField("project").downField("version").as[Option[PackageVersion]]
        homepage <- c.downField("project").downField("homepage").as[String]
        anityaId <- c.downField("project").downField("id").as[AnityaId]
      } yield PackageEdited(
        packageName = projectName,
        packageVersion = projectVersion,
        homepage = homepage,
        anityaId = anityaId
      )
    }
  }
}

final case class NewPackageCreated(
  packageName: PackageName,
  packageVersion: Option[PackageVersion],
  homepage: String,
  anityaId: AnityaId
) extends JsonPayloadParseResult

object NewPackageCreated {
  implicit val newPackageCreatedDecoder: Decoder[NewPackageCreated] = new Decoder[NewPackageCreated] {
    override def apply(c: HCursor): Result[NewPackageCreated] = {
      for {
        projectName <- c.downField("project").downField("name").as[PackageName]
        projectVersion <- c.downField("project").downField("version").as[Option[PackageVersion]]
        homepage <- c.downField("project").downField("homepage").as[String]
        anityaId <- c.downField("project").downField("id").as[AnityaId]
      } yield NewPackageCreated(
        packageName = projectName,
        packageVersion = projectVersion,
        homepage = homepage,
        anityaId = anityaId
      )
    }
  }
}

final case class DependencyUpdate(
  packageName: String,
  packageVersion: String,
  previousVersion: String,
  homepage: String,
  anityaId: Int
) extends JsonPayloadParseResult {
  def printEmailTitle: String = s"Package $packageName was just upgraded from " +
    s"version $previousVersion to $packageVersion"

  def printEmailBody: String = s"The package $packageName was just upgraded from " +
    s"version $previousVersion to $packageVersion. Check out its homepage " +
    s"$homepage for more details."
}

object DependencyUpdate {
  implicit val dependencyUpdateDecoder: Decoder[DependencyUpdate] = new Decoder[DependencyUpdate] {
    override def apply(c: HCursor): Result[DependencyUpdate] = {
      for {
        projectName <- c.downField("project").downField("name").as[String]
        projectVersion <- c.downField("project").downField("version").as[String]
        previousVersion <- c.downField("message").downField("old_version").as[String]
        homepage <- c.downField("project").downField("homepage").as[String]
        anityaId <- c.downField("project").downField("id").as[Int]
      } yield DependencyUpdate(
        packageName = projectName,
        packageVersion = projectVersion,
        previousVersion = previousVersion,
        homepage = homepage,
        anityaId = anityaId
      )
    }
  }

}

