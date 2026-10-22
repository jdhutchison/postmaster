package sh.hutch.postmaster.config

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import sh.hutch.postmaster.services.Crypto
import java.nio.file.Files
import java.nio.file.Path

/** Checks the startup configuration when the application starts. */
@ApplicationScoped
class StartupChecks(private val config: PostmasterConfig) {

  fun onStart(@Observes event: StartupEvent) {
    // Fails startup with a message naming the key, rather than failing on first use
    try {
      Crypto.decodeKey(config.encryptionKey())
    } catch (e: IllegalArgumentException) {
      throw IllegalStateException(e.message, e)
    }
    // Calendar features report errors when khal can't be used; the rest of the application works
    if (!Files.isReadable(Path.of(config.khalConfig()))) {
      LOG.warnf("khal configuration '%s' (postmaster.khal-config) is missing or unreadable", config.khalConfig())
    }
  }

  companion object {
    private val LOG = Logger.getLogger(StartupChecks::class.java)
  }
}
