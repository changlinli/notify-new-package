name := "anitya-frontend"

organization := "com.changlinli"

version := "0.1.0"

scalaVersion := "2.13.0"

lazy val http4sVersion = "0.21.0-M2"

libraryDependencies += "org.typelevel" %% "cats-core" % "2.0.0-M4"
libraryDependencies += "dev.profunktor" %% "fs2-rabbit" % "2.0.0-SNAPSHOT"
libraryDependencies += "dev.profunktor" %% "fs2-rabbit-circe" % "2.0.0-SNAPSHOT"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8"
libraryDependencies += "org.bouncycastle" % "bcprov-jdk15on" % "1.62"
libraryDependencies += "org.bouncycastle" % "bcpkix-jdk15on" % "1.62"
libraryDependencies += "co.fs2" %% "fs2-core" % "1.1.0-M1"
libraryDependencies += "co.fs2" %% "fs2-io" % "1.1.0-M1"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.26"
libraryDependencies += "com.sendgrid" % "sendgrid-java" % "4.4.1"
libraryDependencies += "org.clapper" %% "grizzled-slf4j" % "1.3.4"
libraryDependencies += "org.tpolecat" %% "doobie-core" % "0.8.0-M1"
libraryDependencies += "org.tpolecat" %% "doobie-hikari" % "0.8.0-M1"
libraryDependencies += "org.tpolecat" %% "doobie-scalatest" % "0.8.0-M1"
libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.23.1"
libraryDependencies += "org.http4s" %% "http4s-core" % http4sVersion
libraryDependencies += "org.http4s" %% "http4s-dsl" % http4sVersion
libraryDependencies += "org.http4s" %% "http4s-circe" % http4sVersion
libraryDependencies += "org.http4s" %% "http4s-blaze-server" % http4sVersion
libraryDependencies += "org.http4s" %% "http4s-blaze-client" % http4sVersion
libraryDependencies += "org.http4s" %% "http4s-scalatags" % http4sVersion
libraryDependencies += "com.github.scopt" %% "scopt" % "3.7.1"
libraryDependencies += "com.lihaoyi" %% "scalatags" % "0.7.0"

assemblyMergeStrategy in assembly := {
  // For now we're not going to use anything JDK-9 related
  case "META-INF/versions/9/module-info.class" => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

Test / fork := true
Compile / run / fork := true
connectInput in run := true
