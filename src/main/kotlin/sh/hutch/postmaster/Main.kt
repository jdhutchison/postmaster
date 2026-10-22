package sh.hutch.postmaster

import io.quarkus.runtime.ApplicationLifecycleManager
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Entry point. The single command line argument is the path to the YAML configuration file,
 * which is added as a Quarkus configuration location before the application starts.
 */
@QuarkusMain
class Main : QuarkusApplication {

  override fun run(vararg args: String): Int {
    // Checked again here in case the application is started without going through main(),
    // as dev mode can do
    try {
      configLocation(arrayOf(*args))
    } catch (e: IllegalArgumentException) {
      System.err.println(e.message)
      return EXIT_USAGE
    }
    Quarkus.waitForExit()
    return 0
  }

  companion object {
    /** Exit code used when the command line argument is missing or unusable. */
    const val EXIT_USAGE = 2

    const val USAGE = "Usage: postmaster <path to YAML configuration file>"

    @JvmStatic
    fun main(args: Array<String>) {
      val location = try {
        configLocation(args)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        // The default handler calls System.exit, except under @QuarkusMainTest, where it records
        // the exit code instead of ending the test JVM
        ApplicationLifecycleManager.getDefaultExitCodeHandler().accept(EXIT_USAGE, null)
        return
      }
      System.setProperty("quarkus.config.locations", location)
      Quarkus.run(Main::class.java, *args)
    }

    /**
     * Maps the command line arguments to a configuration location URI.
     *
     * @throws IllegalArgumentException with a message naming the problem when there isn't exactly
     *     one argument, or it isn't a readable regular file
     */
    @JvmStatic
    fun configLocation(args: Array<out String>): String {
      require(args.size == 1) {
        if (args.isEmpty()) {
          "Missing configuration file argument. $USAGE"
        } else {
          "Expected exactly one argument but got ${args.size}. $USAGE"
        }
      }
      val path = try {
        Path.of(args[0]).toAbsolutePath().normalize()
      } catch (e: InvalidPathException) {
        throw IllegalArgumentException("Configuration file path '${args[0]}' is not a valid path. $USAGE")
      }
      require(Files.exists(path)) { "Configuration file '$path' does not exist. $USAGE" }
      require(Files.isRegularFile(path)) { "Configuration file '$path' is not a regular file. $USAGE" }
      require(Files.isReadable(path)) { "Configuration file '$path' is not readable. $USAGE" }
      return path.toUri().toString()
    }
  }
}
