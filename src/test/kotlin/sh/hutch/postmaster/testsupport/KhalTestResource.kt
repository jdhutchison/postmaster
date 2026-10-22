package sh.hutch.postmaster.testsupport

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Creates the temporary khal config, calendar vdirs and fake khal/Chromium
 * executables (see [FakeTools]) and points the startup config at them.
 */
class KhalTestResource : QuarkusTestResourceLifecycleManager {
  override fun start(): Map<String, String> {
    val root = FakeTools.create()
    return mapOf(
        "postmaster.khal-config" to root.resolve("khal.ini").toString(),
        "postmaster.khal-command" to root.resolve("khal/khal").toString(),
        "postmaster.chromium-command" to root.resolve("chromium/chromium").toString()
    )
  }

  override fun stop() {
    FakeTools.delete()
  }
}
