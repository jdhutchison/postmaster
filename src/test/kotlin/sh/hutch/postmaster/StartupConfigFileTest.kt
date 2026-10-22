package sh.hutch.postmaster

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.config.PostmasterConfig
import java.nio.file.Path

// REQ Technical choices / Configuration: the configuration file holds what the
// app needs to start up. Main adds the file named on the command line as a
// config location; this profile does the same with a test file, and the
// application must see its values.
@QuarkusTest
@TestProfile(StartupConfigFileTest.ConfigFileProfile::class)
class StartupConfigFileTest {
  @Inject
  lateinit var config: PostmasterConfig

  @Test
  fun `values in the configuration file are used`() {
    assertEquals("/opt/from-config-file/curl", config.curlCommand())
  }

  class ConfigFileProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
      val file = Path.of(
          StartupConfigFileTest::class.java.getResource("/config/startup-config.yml")!!.toURI()
      )
      return mapOf("quarkus.config.locations" to file.toUri().toString())
    }
  }
}
