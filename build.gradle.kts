import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
  kotlin("jvm")
  kotlin("plugin.allopen")
  kotlin("plugin.jpa")
  kotlin("kapt")
  id("io.quarkus")
}

group = "sh.hutch"

// The version is a build number from version.properties (v1, v2, …): each production build gets
// the next one (CLARIFICATIONS.md R7)
val versionFile = layout.projectDirectory.file("version.properties").asFile
val buildNumber = versionFile.readLines().first { it.trim().startsWith("version=") }.substringAfter("=").trim().toInt()
version = buildNumber.toString()

// The tester agent builds into its own directory (POSTMASTER_BUILD_DIR=build-tests)
// so concurrent builds in this working tree don't overwrite each other
layout.buildDirectory.set(layout.projectDirectory.dir(System.getenv("POSTMASTER_BUILD_DIR") ?: "build"))

repositories {
  mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project
val mapstructVersion: String by project
val commonmarkVersion: String by project
val greenmailVersion: String by project
val wiremockVersion: String by project
val playwrightVersion: String by project

dependencies {
  implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
  implementation(enforcedPlatform("$quarkusPlatformGroupId:quarkus-mcp-server-bom:$quarkusPlatformVersion"))

  implementation("io.quarkus:quarkus-kotlin")
  implementation("org.jetbrains.kotlin:kotlin-stdlib")
  implementation("io.quarkus:quarkus-arc")
  implementation("io.quarkus:quarkus-config-yaml")
  implementation("io.quarkus:quarkus-rest")
  implementation("io.quarkus:quarkus-rest-jackson")
  implementation("io.quarkus:quarkus-rest-qute")
  implementation("io.quarkus:quarkus-qute")
  implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
  implementation("io.quarkus:quarkus-hibernate-validator")
  implementation("io.quarkus:quarkus-jdbc-mariadb")
  implementation("io.quarkus:quarkus-flyway")
  implementation("org.flywaydb:flyway-mysql")
  implementation("io.quarkus:quarkus-scheduler")
  implementation("io.quarkus:quarkus-smallrye-openapi")
  implementation("io.quarkiverse.mcp:quarkus-mcp-server-http")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.jsoup:jsoup")
  implementation("org.eclipse.angus:angus-mail")
  implementation("org.commonmark:commonmark:$commonmarkVersion")
  implementation("org.mapstruct:mapstruct:$mapstructVersion")
  kapt("org.mapstruct:mapstruct-processor:$mapstructVersion")

  testImplementation("io.quarkus:quarkus-junit5")
  testImplementation("io.quarkus:quarkus-junit5-mockito")
  testImplementation("io.quarkus:quarkus-jdbc-h2")
  testImplementation("io.rest-assured:rest-assured")
  testImplementation("org.awaitility:awaitility")
  testImplementation("com.icegreen:greenmail-junit5:$greenmailVersion")
  testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
  testImplementation("com.microsoft.playwright:playwright:$playwrightVersion")
}

// Dev mode runs on an in-memory H2 database (application.yaml "%dev"). The driver is added only
// when quarkusDev runs, so production builds and the native image don't include it.
if (gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "quarkusDev" }) {
  dependencies {
    implementation("io.quarkus:quarkus-jdbc-h2")
  }
}

// The application's argument is its startup configuration file; dev mode uses config/dev.yml
// unless --quarkus-args names another. The JVM property covers starts that skip main().
tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
  val devConfig = layout.projectDirectory.file("config/dev.yml").asFile.absolutePath
  if (args.isEmpty()) {
    args = listOf(devConfig)
  }
  jvmArgs = jvmArgs.orEmpty() + "-Dquarkus.config.locations=file:$devConfig"
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    javaParameters = true
  }
}

allOpen {
  annotation("jakarta.ws.rs.Path")
  annotation("jakarta.enterprise.context.ApplicationScoped")
  annotation("jakarta.enterprise.context.RequestScoped")
  annotation("jakarta.persistence.Entity")
  annotation("jakarta.persistence.MappedSuperclass")
  annotation("jakarta.persistence.Embeddable")
  annotation("io.quarkus.test.junit.QuarkusTest")
}

kapt {
  keepJavacAnnotationProcessors = true
  arguments {
    arg("mapstruct.defaultComponentModel", "jakarta-cdi")
    arg("mapstruct.unmappedTargetPolicy", "ERROR")
  }
}

// kapt's stub generation reads the directory Quarkus generates code into, so Gradle needs it
// ordered after Quarkus code generation (it fails dev mode otherwise)
tasks.matching { it.name == "kaptGenerateStubsKotlin" }.configureEach {
  mustRunAfter("quarkusGenerateCode", "quarkusGenerateCodeDev", "quarkusGenerateCodeTests")
}

// Tests use no annotation processors, and kapt's incremental snapshots for the test source set
// break when main and test change together ("Number of loaded files in snapshots differs")
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
  enabled = false
}

// docs/openapi.yml is generated from the controllers' annotations: Quarkus writes the schema
// while building (quarkus.smallrye-openapi.store-schema-directory) and this copies it into docs/
val openapiSpec = tasks.register<Copy>("openapiSpec") {
  description = "Copies the OpenAPI schema generated by the Quarkus build to docs/openapi.yml"
  from(layout.projectDirectory.dir("build/openapi")) {
    include("openapi.yaml")
    rename { "openapi.yml" }
  }
  into(layout.projectDirectory.dir("docs"))
}

tasks.named("quarkusBuild") {
  finalizedBy(openapiSpec)
  // Once a build has succeeded, the next one gets the next version
  doLast {
    if (!project.hasProperty("keepVersion")) {
      versionFile.writeText(versionFile.readText().replace(Regex("(?m)^version=\\d+"), "version=${buildNumber + 1}"))
      logger.lifecycle("Built v$buildNumber; the next build will be v${buildNumber + 1}")
    }
  }
}

// The commit a build is from, with "-dirty" if there are uncommitted changes. The Docker build
// has no .git, so bin/build-image passes it in as -PgitCommit. Empty if neither is available.
fun git(vararg args: String): String? =
  runCatching {
    providers.exec {
      commandLine("git", *args)
      workingDir = projectDir
      isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
  }.getOrNull()
val gitCommit = providers.gradleProperty("gitCommit").filter { it.isNotBlank() }.orElse(provider {
  val commit = git("rev-parse", "--short=12", "HEAD")?.takeIf { it.matches(Regex("[0-9a-f]+")) } ?: ""
  if (commit.isNotEmpty() && !git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) "$commit-dirty" else commit
})

// build-info.properties, which the status endpoint reads: the version, the commit, and when a
// production build was made (empty for dev mode and tests, so their builds stay up to date)
val buildInfoDir = layout.buildDirectory.dir("generated/build-info")
var releaseBuild = false
gradle.taskGraph.whenReady { releaseBuild = hasTask(":quarkusBuild") }
val buildInfo = tasks.register("buildInfo") {
  description = "Writes build-info.properties with the version, commit and build time"
  inputs.property("version", buildNumber)
  inputs.property("commit", gitCommit)
  // Switching between production and other builds rewrites it
  inputs.property("release", provider { releaseBuild })
  outputs.dir(buildInfoDir)
  outputs.upToDateWhen { !releaseBuild }
  doLast {
    val builtAt = if (releaseBuild) Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() else ""
    val file = buildInfoDir.get().file("build-info.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("version=$buildNumber\ncommit=${gitCommit.get()}\nbuilt-at=$builtAt\n")
  }
}
sourceSets.main {
  resources.srcDir(buildInfo)
}

tasks.withType<Test> {
  useJUnitPlatform()
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
  maxHeapSize = "1536m"
}
