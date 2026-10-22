package sh.hutch.postmaster

import io.quarkus.test.junit.main.QuarkusMainLauncher
import io.quarkus.test.junit.main.QuarkusMainTest
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

// REQ Technical choices: the program takes one command line argument, the
// location of the configuration file. Without a usable one it must not run,
// and must say why.
@QuarkusMainTest
class MainLaunchTest {
  @Test
  fun `exits with an error when no configuration file is given`(launcher: QuarkusMainLauncher) {
    val result = launcher.launch()

    assertNotEquals(0, result.exitCode())
    assertTrue(
        result.errorOutput.lowercase().contains("configuration file"),
        "stderr explains the missing argument: ${result.errorOutput}"
    )
  }

  @Test
  fun `exits with an error when the configuration file does not exist`(launcher: QuarkusMainLauncher) {
    val missing = Files.createTempDirectory("postmaster-main").resolve("missing.yml")

    val result = launcher.launch(missing.toString())

    assertNotEquals(0, result.exitCode())
    assertTrue(result.errorOutput.contains(missing.toString()), "stderr names the file: ${result.errorOutput}")
  }

  @Test
  fun `exits with an error when given more than one argument`(launcher: QuarkusMainLauncher) {
    val result = launcher.launch("one.yml", "two.yml")

    assertNotEquals(0, result.exitCode())
    assertTrue(
        result.errorOutput.lowercase().contains("configuration file"),
        "stderr explains the usage: ${result.errorOutput}"
    )
  }
}
