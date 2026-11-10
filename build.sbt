// Time-locked Vault dApp
//   contract/ : on-chain validator (Scalus) + off-chain transaction builders
//   backend/  : REST API (cask) that builds unsigned transactions via Blockfrost and serves the Elm UI
//   frontend/ : Elm single page app (built separately with `npm run build` in frontend/)

val scalusVersion = "1.5.0"

ThisBuild / scalaVersion := "3.3.9"
ThisBuild / organization := "dev.vault"
ThisBuild / version := "0.1.0"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")

lazy val root = (project in file("."))
    .aggregate(contract, backend)
    .settings(
      name := "cardano-vault",
      publish / skip := true
    )

lazy val contract = (project in file("contract"))
    .settings(
      name := "vault-contract",
      // The Scalus compiler plugin turns @Compile objects into Plutus Core
      addCompilerPlugin("org.scalus" % "scalus-plugin" % scalusVersion cross CrossVersion.full),
      libraryDependencies ++= Seq(
        "org.scalus" %% "scalus" % scalusVersion,
        "org.scalus" %% "scalus-cardano-ledger" % scalusVersion,
        "org.scalus" %% "scalus-testkit" % scalusVersion % Test,
        "org.scalatest" %% "scalatest" % "3.2.20" % Test
      ),
      Test / fork := true
    )

lazy val backend = (project in file("backend"))
    .enablePlugins(JavaAppPackaging)
    .dependsOn(contract)
    .settings(
      name := "vault-backend",
      libraryDependencies ++= Seq(
        "com.lihaoyi" %% "cask" % "0.11.3",
        "org.scalus" %% "scalus-testkit" % scalusVersion,
        "org.scalatest" %% "scalatest" % "3.2.20" % Test
      ),
      // Run from the repo root so the server finds frontend/public
      run / fork := true,
      run / baseDirectory := (ThisBuild / baseDirectory).value,
      run / connectInput := true,
      Compile / mainClass := Some("vault.server.VaultServer")
    )
