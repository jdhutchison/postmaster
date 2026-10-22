package sh.hutch.postmaster.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

/** Startup configuration from the YAML configuration file (prefix `postmaster`). */
@ConfigMapping(prefix = "postmaster")
interface PostmasterConfig {

  /** Base64 encoding of exactly 32 random bytes: the AES-256 key for encrypted settings. */
  fun encryptionKey(): String

  /** Path to the khal configuration file. */
  fun khalConfig(): String

  /** Command used to run khal. */
  @WithDefault("khal")
  fun khalCommand(): String

  /** Command used to run headless Chromium. */
  @WithDefault("chromium")
  fun chromiumCommand(): String

  /** Command used to run curl. */
  @WithDefault("curl")
  fun curlCommand(): String
}
