package sh.hutch.postmaster.config

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import sh.hutch.postmaster.util.Times
import java.time.Instant
import java.util.Properties

/**
 * Which build this is and when it started (CLARIFICATIONS.md R7). The version and build time come
 * from `build-info.properties`, which Gradle writes: the version is the build number from
 * `version.properties`, the commit is git's, and the build time is set only for production builds.
 */
@ApplicationScoped
class BuildInfo {
  private val properties = Properties().apply {
    BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
  }

  /** The build number, e.g. `12`; `dev` if the build info is missing. */
  val buildNumber: String = properties.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"

  /** The version as shown: `v12`. */
  val version: String
    get() = if (buildNumber.all { it.isDigit() }) "v$buildNumber" else buildNumber

  /** The commit the build is from, e.g. `1626e57a0b3c`, with `-dirty` if it had uncommitted changes; null if unknown. */
  val commit: String? = properties.getProperty("commit")?.trim()?.takeIf { it.isNotEmpty() }

  /** When the build was made; null for dev mode and test builds. */
  val builtAt: Instant? = properties.getProperty("built-at")?.trim()?.takeIf { it.isNotEmpty() }?.let { Instant.parse(it) }

  /** When the application started. */
  var startedAt: Instant = Times.now()
    protected set

  fun onStart(@Observes event: StartupEvent) {
    startedAt = Times.now()
  }
}
