package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.models.DownloadedFile
import sh.hutch.postmaster.util.IsoDurations
import sh.hutch.postmaster.util.Processes
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fetches web pages with headless Chromium (so pages built by JavaScript work) and downloads files
 * with curl, both presenting the fetch.user_agent setting (Chrome on Windows) and limited by
 * fetch.timeout.
 */
@ApplicationScoped
class HttpFetcher(private val config: PostmasterConfig, private val settings: SettingsService) {

  /** The page's DOM after it has loaded, as HTML. */
  fun fetchPage(url: String): String {
    checkUrl(url)
    val timeout = settings.getDuration(SettingKeys.FETCH_TIMEOUT)
    val command = listOf(
        config.chromiumCommand(), "--headless=new", "--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage",
        "--no-first-run", "--user-agent=" + settings.getString(SettingKeys.FETCH_USER_AGENT).orEmpty(),
        "--dump-dom", url
    )
    val result = try {
      Processes.run(command, timeout)
    } catch (e: IOException) {
      throw FetchException("The browser (${config.chromiumCommand()}) could not be started: ${e.message}", e)
    }
    if (result.timedOut) {
      throw FetchException("Fetching $url took longer than ${IsoDurations.format(timeout)}")
    }
    if (result.exitCode != 0 || result.stdout.isBlank()) {
      throw FetchException("Fetching $url failed: ${result.stderr.trim().lines().lastOrNull().orEmpty().take(300)}")
    }
    return result.stdout
  }

  /** Downloads [url] to [target], creating its directory. */
  fun download(url: String, target: Path): DownloadedFile {
    checkUrl(url)
    val timeout = settings.getDuration(SettingKeys.FETCH_TIMEOUT)
    try {
      target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    } catch (e: IOException) {
      throw FetchException("Could not create the directory for $target: ${e.message}", e)
    }
    val command = listOf(
        config.curlCommand(), "--silent", "--show-error", "--location", "--fail",
        "--max-time", timeout.toSeconds().coerceAtLeast(1).toString(),
        "--user-agent", settings.getString(SettingKeys.FETCH_USER_AGENT).orEmpty(),
        "--output", target.toString(), "--write-out", "%{content_type}", url
    )
    val result = try {
      Processes.run(command, timeout.plusSeconds(5))
    } catch (e: IOException) {
      throw FetchException("curl (${config.curlCommand()}) could not be started: ${e.message}", e)
    }
    if (result.timedOut || result.exitCode != 0) {
      val reason = if (result.timedOut) "it took longer than ${IsoDurations.format(timeout)}" else result.stderr.trim().take(300)
      throw FetchException("Downloading $url failed: $reason")
    }
    val size = try {
      Files.size(target)
    } catch (e: IOException) {
      throw FetchException("Downloading $url produced no file", e)
    }
    return DownloadedFile(target, result.stdout.trim().ifEmpty { null }, size)
  }

  private fun checkUrl(url: String) {
    val scheme = try {
      URI(url).scheme?.lowercase()
    } catch (e: URISyntaxException) {
      null
    }
    if (scheme != "http" && scheme != "https") {
      throw FetchException("Only http and https links can be fetched, not '$url'")
    }
  }
}
