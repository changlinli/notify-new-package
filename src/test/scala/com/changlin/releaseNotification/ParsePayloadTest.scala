package com.changlin.releaseNotification

import com.changlinli.releaseNotification.data.{PackageName, PackageVersion}
import com.changlinli.releaseNotification.ids.AnityaId
import com.changlinli.releaseNotification.{DependencyUpdate, JsonPayloadParseResult, NewPackageCreated, PackageEdited}
import org.scalatest._

class ParsePayloadTest extends FlatSpec with Matchers {

  val json =
    """
       {
  "project" : {
    "id" : 20152,
    "name" : "check_gitlab",
    "homepage" : "https://gitlab.com/6uellerBpanda/check_gitlab",
    "regex" : null,
    "backend" : "GitLab",
    "version_url" : null,
    "version" : "0.4.0",
    "versions" : [
      "0.4.0",
      "0.3.2",
      "0.3",
      "0.2.2",
      "0.2.1",
      "0.2.0",
      "0.2",
      "0.1.1",
      "v.0.3.1"
    ],
    "created_on" : 1554750022.0,
    "updated_on" : 1562184037.0,
    "ecosystem" : "https://gitlab.com/6uellerBpanda/check_pve"
  },
  "distro" : null,
  "message" : {
    "project" : {
      "id" : 20152,
      "name" : "check_gitlab",
      "homepage" : "https://gitlab.com/6uellerBpanda/check_gitlab",
      "regex" : null,
      "backend" : "GitLab",
      "version_url" : null,
      "version" : "0.4.0",
      "versions" : [
        "0.4.0",
        "0.3.2",
        "0.3",
        "0.2.2",
        "0.2.1",
        "0.2.0",
        "0.2",
        "0.1.1",
        "v.0.3.1"
      ],
      "created_on" : 1554750022.0,
      "updated_on" : 1562184037.0,
      "ecosystem" : "https://gitlab.com/6uellerBpanda/check_pve"
    },
    "upstream_version" : "0.4.0",
    "old_version" : "0.3.2",
    "packages" : [
      {
        "package_name" : "monitoring-plugins-gitlab",
        "distro" : "openSUSE"
      }
    ],
    "versions" : [
      "0.4.0",
      "0.3.2",
      "0.3",
      "0.2.2",
      "0.2.1",
      "0.2.0",
      "0.2",
      "0.1.1",
      "v.0.3.1"
    ],
    "ecosystem" : "https://gitlab.com/6uellerBpanda/check_pve",
    "agent" : "anitya",
    "odd_change" : false
  }
}
    """.stripMargin

  val packageCreationJson =
    """
{
    "distro": null,
    "message": {
        "agent": "monkeysonhigh",
        "project": "a-test-project"
    },
    "project": {
        "backend": "GitHub",
        "created_on": 1568268732.0,
        "ecosystem": "https://github.com/changlinli/test-updates",
        "homepage": "https://github.com/changlinli/test-updates",
        "id": 21811,
        "name": "a-test-project",
        "regex": null,
        "updated_on": 1568268732.0,
        "version": null,
        "version_url": "changlinli/test-updates",
        "versions": []
    }
}
    """.stripMargin

  val packageEditJson =
    """
{
    "distro": null,
    "message": {
        "agent": "monkeysonhigh",
        "changes": {
            "name": {
                "new": "a-test-project-blah",
                "old": "a-test-project"
            }
        },
        "fields": [
            "name"
        ],
        "project": "a-test-project-blah"
    },
    "project": {
        "backend": "GitHub",
        "created_on": 1568268732.0,
        "ecosystem": "https://github.com/changlinli/test-updates",
        "homepage": "https://github.com/changlinli/test-updates",
        "id": 21811,
        "name": "a-test-project-blah",
        "regex": null,
        "updated_on": 1568269612.0,
        "version": null,
        "version_url": "changlinli/test-updates",
        "versions": []
    }
}
    """.stripMargin

  "Parsing a payload" should "succeed with an example JSON" in {
    io.circe.parser.parse(json).toOption.get.as[JsonPayloadParseResult] should be (Right(DependencyUpdate(
      packageName = "check_gitlab",
      packageVersion = "0.4.0",
      previousVersion = "0.3.2",
      homepage = "https://gitlab.com/6uellerBpanda/check_gitlab",
      anityaId = 20152
    )))
  }

  "Parsing a package creation payload" should "succeed with an example JSON" in {
    io.circe.parser.parse(packageCreationJson).toOption.get.as[JsonPayloadParseResult] should be (Right(NewPackageCreated(
      packageName = PackageName("a-test-project"),
      packageVersion = None,
      homepage = "https://github.com/changlinli/test-updates",
      anityaId = AnityaId(21811)
    )))
  }

  "Parsing a package edit payload" should "succeed with an example JSON" in {
    io.circe.parser.parse(packageEditJson).toOption.get.as[JsonPayloadParseResult] should be (Right(PackageEdited(
      packageName = PackageName("a-test-project-blah"),
      packageVersion = None,
      homepage = "https://github.com/changlinli/test-updates",
      anityaId = AnityaId(21811)
    )))
  }

}
