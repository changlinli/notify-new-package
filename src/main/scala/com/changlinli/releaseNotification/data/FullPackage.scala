package com.changlinli.releaseNotification.data

import com.changlinli.releaseNotification.ids.{AnityaId, PackageId}
import com.changlinli.releaseNotification.orphanInstances.OrphanInstances._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s.Uri.Host

final case class FullPackage(name: PackageName, homepage: String, anityaId: Int, packageId: Int, currentVersion: PackageVersion)

object FullPackage {
  implicit val fullPackageDecoder: Decoder[FullPackage] = deriveDecoder
  implicit val fullPackageEncoder: Encoder[FullPackage] = deriveEncoder
}

final case class FullPackageWithUnsubscribe(
  name: PackageName,
  homepage: Host,
  anityaId: AnityaId,
  packageId: PackageId
)

object FullPackageWithUnsubscribe {
  implicit val fullPackageWithUnsubscribeDecoder: Decoder[FullPackageWithUnsubscribe] = {
    implicitly[Decoder[PackageName]]
    implicitly[Decoder[Host]]
    implicitly[Decoder[AnityaId]]
    implicitly[Decoder[PackageId]]
    deriveDecoder
  }
  implicit val fullPackageWithUnsubscribeEncoder: Encoder[FullPackageWithUnsubscribe] = deriveEncoder
}
