name := "visitor-processing-service"
organization := "com.vms.processing"
version := "1.0-SNAPSHOT"
lazy val root = (project in file(".")).enablePlugins(PlayScala)
scalaVersion := "2.13.16"
val jacksonVersion = "2.14.3"

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,
  // JDBC
  "org.playframework" %% "play-slick"            % "6.1.0",
  "org.playframework" %% "play-slick-evolutions" % "6.1.0",
  "mysql" % "mysql-connector-java" % "8.0.26",
  // Flyway (optional)
  "org.flywaydb" % "flyway-core" % "9.22.0",
  // AWS S3 SDK v2
  "software.amazon.awssdk" % "s3" % "2.20.60",
  // Kafka client
  "org.apache.kafka" % "kafka-clients" % "3.7.0",
  "com.typesafe.akka" %% "akka-stream" % "2.6.20",
  "com.typesafe.akka" %% "akka-actor" % "2.6.20",
  // Akka (scheduling)
  "com.typesafe.akka" %% "akka-actor" % "2.6.21",
  "com.typesafe.akka" %% "akka-slf4j" % "2.6.21",
  // JSON
  "com.typesafe.play" %% "play-json" % "2.9.4",
  // Logging
  "ch.qos.logback" % "logback-classic" % "1.2.13",
  // Test
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.0" % Test
)
TwirlKeys.templateImports += "controllers.routes"
dependencyOverrides ++= Seq(
  "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion
)

