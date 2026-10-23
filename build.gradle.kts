plugins {
  kotlin("jvm")
  kotlin("plugin.allopen")
  kotlin("plugin.jpa")
  kotlin("kapt")
  id("io.quarkus")
}

group = "sh.hutch"
version = "0.1.0-SNAPSHOT"

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

tasks.withType<Test> {
  useJUnitPlatform()
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
  maxHeapSize = "1536m"
}
