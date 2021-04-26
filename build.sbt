name := "amqp-example"
organization := "pl.org.lenart"
scalaVersion := "2.12.13"

libraryDependencies ++= {
  val akkaStreamAlpakkaV   = "2.0.2"
  val akkaV                = "2.5.32"
  val scalaLoggingV        = "3.9.3"
  val logbackV             = "1.2.3"
  val sttpV                = "3.3.0-RC5"
  val scalatestV           = "3.2.7"
  val mockitoScalaV        = "1.16.37"
  val testcontainersScalaV = "0.39.3"

  Seq(
    "com.lightbend.akka"            %% "akka-stream-alpakka-amqp"       % akkaStreamAlpakkaV,
    "com.typesafe.akka"             %% "akka-slf4j"                     % akkaV,
    "com.typesafe.scala-logging"    %% "scala-logging"                  % scalaLoggingV,
    "ch.qos.logback"                 % "logback-classic"                % logbackV,
    "com.softwaremill.sttp.client3" %% "core"                           % sttpV,
    "com.softwaremill.sttp.client3" %% "akka-http-backend"              % sttpV,
    "org.scalatest"                 %% "scalatest"                      % scalatestV           % Test,
    "org.mockito"                   %% "mockito-scala"                  % mockitoScalaV        % Test,
    "com.typesafe.akka"             %% "akka-testkit"                   % akkaV                % Test,
    "com.typesafe.akka"             %% "akka-stream-testkit"            % akkaV                % Test,
    "com.dimafeng"                  %% "testcontainers-scala-scalatest" % testcontainersScalaV % Test,
    "com.dimafeng"                  %% "testcontainers-scala-rabbitmq"  % testcontainersScalaV % Test
  )
}
