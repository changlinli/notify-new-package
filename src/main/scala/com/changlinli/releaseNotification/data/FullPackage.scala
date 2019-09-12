package com.changlinli.releaseNotification.data

import cats.Order
import cats.instances.all._
import com.changlinli.releaseNotification.ids.{AnityaId, PackageId}
import com.changlinli.releaseNotification.orphanInstances.OrphanInstances._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s.Uri.Host

// If you add a field here, make sure to add an else if clause to FullPackage.fullPackageOrder
final case class FullPackage(name: PackageName, homepage: String, anityaId: Int, packageId: Int, currentVersion: PackageVersion)

object FullPackage {
  implicit val fullPackageDecoder: Decoder[FullPackage] = deriveDecoder
  implicit val fullPackageEncoder: Encoder[FullPackage] = deriveEncoder
  implicit val fullPackageOrder: Order[FullPackage] = new Order[FullPackage] {
    override def compare(x: FullPackage, y: FullPackage): Int = {
      val nameResult = Order[String].compare(x.name.str, y.name.str)
      val homepageResult = Order[String].compare(x.homepage, y.homepage)
      val anityaIdResult = Order[Int].compare(x.anityaId, y.anityaId)
      val packageIdResult = Order[Int].compare(x.packageId, y.packageId)
      val currentVersionResult = Order[String].compare(x.currentVersion.str, y.currentVersion.str)
      if (nameResult != 0) {
        nameResult
      } else if (homepageResult != 0) {
        homepageResult
      } else if (anityaIdResult != 0) {
        anityaIdResult
      } else if (packageIdResult != 0) {
        packageIdResult
      } else if (currentVersionResult != 0) {
        currentVersionResult
      } else {
        0
      }
    }
  }
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
