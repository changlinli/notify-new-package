package com.changlin.releaseNotification

import com.changlinli.releaseNotification.Main
import com.changlinli.releaseNotification.Main.DependencyUpdate
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


  "Parsing a payload" should "succeed with an example JSON" in {
    Main.parsePayload(io.circe.parser.parse(json).toOption.get) should be (Right(DependencyUpdate("awer", "awerawer ", "Awerawer", "awerawer")))
  }
}
