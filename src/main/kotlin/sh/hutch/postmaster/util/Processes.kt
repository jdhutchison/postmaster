package sh.hutch.postmaster.util

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The outcome of running a command. */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

object Processes {
  /**
   * Runs [command] (no shell) and waits up to [timeout], killing it if it takes longer.
   *
   * @throws IOException if the command can't be started
   */
  fun run(command: List<String>, timeout: Duration, input: String? = null): ProcessResult {
    val process = ProcessBuilder(command).start()
    val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8) }
    val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8) }
    process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray(StandardCharsets.UTF_8)) } }
    val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
    if (!finished) {
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
      process.waitFor(5, TimeUnit.SECONDS)
    }
    return ProcessResult(
        if (finished) process.exitValue() else -1,
        runCatching { stdout.get(5, TimeUnit.SECONDS) }.getOrDefault(""),
        runCatching { stderr.get(5, TimeUnit.SECONDS) }.getOrDefault(""),
        !finished
    )
  }
}
