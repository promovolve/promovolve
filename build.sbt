import scala.collection.Seq

ThisBuild / organization := "promovolve"
scalaVersion := "3.7.4"
val pekkoVersion                = "1.4.0"
val pekkoHttpVersion            = "1.2.0"
val pekkoProjectionVersion      = "1.1.0"
val pekkoManagementVersion      = "1.2.1"
val logbackVersion              = "1.5.18"
val pekkoQuartzSchedulerVersion = "1.3.0-pekko-1.1.x"
val tapirVersion = "1.13.3"

ThisBuild / scalacOptions :=
  Seq(
    "-feature",
    "-unchecked",
    "-deprecation",
    "-encoding", "utf8",
    "-Xmax-inlines:128",
    // Permanent guardrails for the two unused-symbol categories with the
    // highest signal-to-noise ratio. Imports are mechanical to clean up
    // (never false-positive); private members nobody calls are real dead
    // code. Explicit/implicit/pattern unused-warnings are off — those
    // need case-by-case judgment and the warning churn isn't worth it.
    "-Wunused:imports",
    "-Wunused:privates",
  )

// Fork JVM for run to keep it alive
Compile / run / fork := true
Compile / run / connectInput := true

// JVM flags for Java 21+ compatibility (LMDB durable DData requires access to java.nio internals)
Compile / run / javaOptions ++= Seq(
  "--add-opens", "java.base/java.nio=ALL-UNNAMED",
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
)

lazy val root = (project in file("."))
  .aggregate(core, browser, api)
  .settings(
    publish / skip := true
  )

lazy val core = (project in file("modules/core"))
  .dependsOn(browser)
  .settings(commonSettings)
  .settings(
    // Protobuf (scalapb) — Phase 1 of the wire-format migration: cluster
    // TRANSPORT messages move to schema-defined protos (persistence stays
    // Jackson). Protos live in modules/core/src/main/protobuf/.
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion,
    ),
  )

lazy val browser = (project in file("modules/browser"))
  .settings(commonSettings)

lazy val api = (project in file("modules/api"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    // Cluster formation via Kubernetes API discovery (multi-pod sharding).
    // Only started when PEKKO_CLUSTER_BOOTSTRAP=on; inert otherwise.
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-management"                   % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http"      % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-discovery-kubernetes-api"     % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-discovery"                    % pekkoVersion,
    ),
    Compile / mainClass := Some("promovolve.api.Main"),
    // Fork JVM for run to keep it alive
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    // Pass environment variables to forked JVM
    Compile / run / envVars ++= sys.env,
    // JVM flags for Java 21+ compatibility (LMDB durable DData requires access to java.nio internals)
    Compile / run / javaOptions ++= Seq(
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
      "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    // Fast dev loop (sbt-revolver): `sbt "api / ~reStart"` (scripts/dev.sh)
    // recompiles + restarts the API on every source change — same fork/env as
    // `run`, but auto, so a .scala edit is live on :8080 in ~10-20s instead of
    // a 5-min Docker image + k8s roll. reStart doesn't inherit Compile/run, so
    // mirror the JVM flags + env here.
    reStart / mainClass := Some("promovolve.api.Main"),
    reStart / envVars := sys.env,
    reStart / javaOptions ++= Seq(
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
      "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    // Bake the same --add-opens flags into the `sbt api/stage` launcher
    // (modules/api/target/universal/stage/bin/api) so the container image
    // doesn't have to re-declare them. Used by Dockerfile.api.
    bashScriptExtraDefines ++= Seq(
      """addJava "--add-opens=java.base/java.nio=ALL-UNNAMED"""",
      """addJava "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED""""
    )
  )

lazy val commonSettings = Seq(
  scalaVersion := "3.7.4",
  libraryDependencies ++= Seq(
    // Pekko
    "org.apache.pekko"       %% "pekko-actor-typed"            % pekkoVersion,
    "org.apache.pekko"       %% "pekko-cluster-typed"          % pekkoVersion,
    "org.apache.pekko"       %% "pekko-cluster-sharding-typed" % pekkoVersion,
    "org.apache.pekko"       %% "pekko-stream-typed"           % pekkoVersion,
    "org.apache.pekko"       %% "pekko-persistence-typed"      % pekkoVersion,
    "org.apache.pekko"       %% "pekko-serialization-jackson"  % pekkoVersion,
    "org.apache.pekko"       %% "pekko-http"                   % pekkoHttpVersion,
    "org.apache.pekko"       %% "pekko-http-spray-json"        % pekkoHttpVersion,
    "org.apache.pekko"       %% "pekko-http-xml"               % pekkoHttpVersion,  // Explicit version to avoid conflict with pekko-connectors-s3
    // Persistence (PostgreSQL)
    "org.apache.pekko"       %% "pekko-persistence-jdbc"       % "1.2.0",
    "com.typesafe.slick"     %% "slick"                        % "3.5.1",
    "com.typesafe.slick"     %% "slick-hikaricp"               % "3.5.1",
    "org.postgresql"          % "postgresql"                   % "42.7.8",
    // Pekko Projection (for dashboard read-side)
    "org.apache.pekko"       %% "pekko-projection-core"        % pekkoProjectionVersion,
    "org.apache.pekko"       %% "pekko-projection-slick"       % pekkoProjectionVersion,
    // Utilities
    "io.github.samueleresca" %% "pekko-quartz-scheduler"       % pekkoQuartzSchedulerVersion,
    "ch.qos.logback"          % "logback-classic"              % logbackVersion,
    "com.github.jkugiya"     %% "ulid-scala"                   % "1.0.6",
    "com.microsoft.playwright" % "playwright"                  % "1.54.0",
    "org.apache.commons"      % "commons-math3"                % "3.6.1",  // Beta distribution for Thompson Sampling
    "com.github.blemale"     %% "scaffeine"                    % "5.3.0",  // Scala wrapper for Caffeine cache
    "com.google.guava"        % "guava"                        % "33.4.8-jre",  // InternetDomainName (public-suffix eTLD+1 for auto-approve trust)
    "com.sksamuel.scrimage"   % "scrimage-webp"                % "4.3.0",   // WebP encoder (bundles cwebp/dwebp native binaries)
    "org.apache.pekko"       %% "pekko-connectors-s3"          % "1.2.0",   // S3/R2 connector (streaming)
    "com.softwaremill.sttp.tapir" %% "tapir-pekko-http-server" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-json-spray"        % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"      % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
    // Test
    "org.scalatest"          %% "scalatest"                    % "3.2.19"     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"              % "3.2.18.0"   % Test,
    "org.apache.pekko"       %% "pekko-actor-testkit-typed"    % pekkoVersion % Test,
    "org.apache.pekko"       %% "pekko-testkit"                % pekkoVersion % Test,
    "org.apache.pekko"       %% "pekko-stream-testkit"         % pekkoVersion % Test,
    "org.apache.pekko"       %% "pekko-persistence-testkit"    % pekkoVersion % Test
  )
)
