package sh.hutch.postmaster.testsupport

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector

/**
 * Marks a `GreenMail` field to receive the running server:
 * `@field:InjectGreenMail lateinit var greenMail: GreenMail`
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class InjectGreenMail

/**
 * An in-memory IMAP and SMTP server standing in for Dovecot and the SMTP relay.
 * Ports are dynamic so parallel test runs don't collide. The mailbox account is
 * [USER] / [PASSWORD]. Point the IMAP and SMTP settings at it with the ports
 * from the injected server.
 */
class GreenMailTestResource : QuarkusTestResourceLifecycleManager {
  private lateinit var greenMail: GreenMail

  override fun start(): Map<String, String> {
    greenMail = GreenMail(arrayOf(ServerSetupTest.IMAP.dynamicPort(), ServerSetupTest.SMTP.dynamicPort()))
    greenMail.start()
    greenMail.setUser(ADDRESS, USER, PASSWORD)
    return emptyMap()
  }

  override fun inject(testInjector: TestInjector) {
    testInjector.injectIntoFields(
        greenMail,
        TestInjector.AnnotatedAndMatchesType(InjectGreenMail::class.java, GreenMail::class.java)
    )
  }

  override fun stop() {
    greenMail.stop()
  }

  companion object {
    const val ADDRESS = "proton@fulcrum.test"
    const val USER = "proton@fulcrum.test"
    const val PASSWORD = "mailbox-secret"
  }
}
