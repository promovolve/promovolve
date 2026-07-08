addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")
// Fast dev loop: `sbt "api / ~reStart"` (scripts/dev.sh) hot-reloads the API.
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")
// Protobuf wire format for cluster messages (Phase 1: transport-only, see
// docs — persistence stays Jackson). scalapb generates Scala case classes
// from .proto; schema-defined messages make the jackson-null class of bug
// (bare tuples deserializing to null → Artery inbound stream death)
// structurally impossible.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17"
