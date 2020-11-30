name := "claim-spark-tokens"

version := "0.1"

scalaVersion := "2.13.4"

/** Enabling ScalaJS */
enablePlugins(ScalaJSPlugin)

/** Enabling Scalably typed, with scala-js-bundler */
enablePlugins(ScalablyTypedConverterPlugin)

scalaJSUseMainModuleInitializer := true

libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "1.1.0"

npmDependencies in Compile += "ripple-lib" -> "1.8.2"

npmDependencies in Compile += "ethereumjs-wallet" -> "1.0.1"

useYarn := true
