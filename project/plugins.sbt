/** Explicitly adding dependency on Scala.js */
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.3.1")

/** Plugin for generating Scala.js facades from TypeScript declaration file. */
resolvers += Resolver.bintrayRepo("oyvindberg", "converter")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter" % "1.0.0-beta29")

/** Plugin for managing npm dependencies. */
addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.20.0")
