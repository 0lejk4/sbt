ThisBuild / scalaVersion := "2.12.8"

def localCache =
	ivyPaths := IvyPaths(baseDirectory.value, Some((baseDirectory in ThisBuild).value / "ivy" / "cache"))

val b = project
  .settings(
    localCache,
    libraryDependencies += "org.example" %% "artifacta" % "1.0.0-SNAPSHOT" withSources() classifier("tests"),
    externalResolvers := Vector(
      MavenCache("demo", ((baseDirectory in ThisBuild).value / "demo-repo")),
      DefaultMavenRepository
    )
  )

val a = project
  .settings(
    localCache,
    organization := "org.example",
    name := "artifacta",
    version := "1.0.0-SNAPSHOT",
    publishArtifact in (Test,packageBin) := true,
    publishTo := Some(MavenCache("demo", ((baseDirectory in ThisBuild).value / "demo-repo")))
  )
