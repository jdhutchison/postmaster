pluginManagement {
  val quarkusPluginVersion: String by settings
  val kotlinVersion: String by settings
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
  plugins {
    id("io.quarkus") version quarkusPluginVersion
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.allopen") version kotlinVersion
    kotlin("plugin.jpa") version kotlinVersion
    kotlin("kapt") version kotlinVersion
  }
}

rootProject.name = "postmaster"
