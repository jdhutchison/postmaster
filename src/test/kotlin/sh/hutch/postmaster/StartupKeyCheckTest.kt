package sh.hutch.postmaster

import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.main.QuarkusMainLauncher
import io.quarkus.test.junit.main.QuarkusMainTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

// REQ Configuration: the configuration file holds the configuration
// encryption key; CLAR S7 (AES-256). docs/dev/contracts.md: a key that isn't
// base64 of 32 bytes stops startup with a message naming the key.
@QuarkusMainTest
@TestProfile(StartupKeyCheckTest.ShortKey::class)
class StartupKeyCheckTest {
  @Test
  fun `a bad encryption key stops startup and names the key`(launcher: QuarkusMainLauncher) {
    val result = launcher.launch(configFile())

    assertNotEquals(0, result.exitCode())
    val output = result.output + "\n" + result.errorOutput
    assertTrue(output.contains("postmaster.encryption-key"), "output names the key:\n$output")
  }

  private fun configFile(): String =
      Path.of(StartupKeyCheckTest::class.java.getResource("/config/startup-config.yml")!!.toURI()).toString()

  /** Base64 of 16 bytes: an AES-128 key, not the AES-256 key required */
  class ShortKey : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("postmaster.encryption-key" to "MDEyMzQ1Njc4OWFiY2RlZg==")
  }

  companion object {
    @JvmStatic
    @AfterAll
    fun forgetConfigLocation() {
      // main() sets this JVM-wide for a valid config file argument (docs/dev/contracts.md)
      System.clearProperty("quarkus.config.locations")
    }
  }
}
