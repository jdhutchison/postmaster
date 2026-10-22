package sh.hutch.postmaster.testsupport

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector

/** Marks a `WireMockServer` field to receive the running server: `@field:InjectWireMock lateinit var wireMock: WireMockServer` */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class InjectWireMock

/**
 * A WireMock server standing in for the llama.cpp LLM and for web pages and
 * files fetched over HTTP. The port is dynamic; point the LLM address setting
 * and test links at [WireMockServer.baseUrl].
 */
class WireMockTestResource : QuarkusTestResourceLifecycleManager {
  private lateinit var server: WireMockServer

  override fun start(): Map<String, String> {
    server = WireMockServer(options().dynamicPort())
    server.start()
    return emptyMap()
  }

  override fun inject(testInjector: TestInjector) {
    testInjector.injectIntoFields(
        server,
        TestInjector.AnnotatedAndMatchesType(InjectWireMock::class.java, WireMockServer::class.java)
    )
  }

  override fun stop() {
    server.stop()
  }
}
