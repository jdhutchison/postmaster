package sh.hutch.postmaster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// REQ Technical choices: "The program needs to take one command line argument,
// the location of the configuration file."
class MainArgumentsTest {
  @TempDir
  lateinit var dir: Path

  @Test
  fun `one readable file argument becomes its absolute file URI`() {
    val config = Files.writeString(dir.resolve("postmaster.yml"), "postmaster: {}\n")

    assertEquals(config.toUri().toString(), Main.configLocation(arrayOf(config.toString())))
  }

  @Test
  fun `a relative path is resolved against the working directory`() {
    val config = Files.writeString(dir.resolve("relative.yml"), "postmaster: {}\n")
    val relative = Path.of("").toAbsolutePath().relativize(config)

    assertEquals(config.toUri().toString(), Main.configLocation(arrayOf(relative.toString())))
  }

  @Test
  fun `no argument is rejected with a message asking for the configuration file`() {
    val error = assertThrows<IllegalArgumentException> { Main.configLocation(emptyArray()) }

    assertMentionsConfigurationFile(error)
  }

  @Test
  fun `more than one argument is rejected`() {
    val config = Files.writeString(dir.resolve("postmaster.yml"), "postmaster: {}\n")

    val error = assertThrows<IllegalArgumentException> {
      Main.configLocation(arrayOf(config.toString(), config.toString()))
    }

    assertMentionsConfigurationFile(error)
  }

  @Test
  fun `a missing file is rejected with a message naming it`() {
    val missing = dir.resolve("missing.yml")

    val error = assertThrows<IllegalArgumentException> { Main.configLocation(arrayOf(missing.toString())) }

    assertTrue(error.message!!.contains(missing.toString()), "message names the file: ${error.message}")
  }

  @Test
  fun `a directory is rejected`() {
    val error = assertThrows<IllegalArgumentException> { Main.configLocation(arrayOf(dir.toString())) }

    assertTrue(error.message!!.contains(dir.toString()), "message names the path: ${error.message}")
  }

  private fun assertMentionsConfigurationFile(error: IllegalArgumentException) {
    val message = error.message.orEmpty().lowercase()
    assertTrue(message.contains("configuration file"), "message mentions the configuration file: $message")
  }
}
