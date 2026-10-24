package sh.hutch.postmaster.config

import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dev mode's configuration (CLAR R2): `./gradlew quarkusDev` runs on an
 * in-memory H2 database in MariaDB mode, with scheduling off, a dev key and
 * its files under build/dev, and the Gradle task passes config/dev.yml.
 */
class DevProfileConfigTest {
  private fun dev(): SmallRyeConfig =
      SmallRyeConfigBuilder()
          .withProfile("dev")
          .withSources(YamlConfigSource(Path.of("src/main/resources/application.yaml").toUri().toURL()))
          .build()

  @Test
  fun `dev mode uses an in-memory database in MariaDB mode`() {
    val config = dev()

    assertEquals("h2", config.getRawValue("quarkus.datasource.db-kind"))
    val url = config.getRawValue("quarkus.datasource.jdbc.url")
    assertTrue(url.startsWith("jdbc:h2:mem:") && url.contains("MODE=MariaDB"), url)
  }

  @Test
  fun `dev mode needs no mail server, scheduler or key of your own`() {
    val config = dev()

    assertEquals("false", config.getRawValue("postmaster.scheduler-enabled"))
    assertEquals("build/dev/khal.ini", config.getRawValue("postmaster.khal-config"))
    assertTrue(config.getRawValue("postmaster.encryption-key").isNotBlank())
  }

  @Test
  fun `the Gradle dev task passes config dev yml`() {
    assertTrue(Files.isRegularFile(Path.of("config/dev.yml")))
    assertTrue(Files.readString(Path.of("build.gradle.kts")).contains("config/dev.yml"))
  }
}
