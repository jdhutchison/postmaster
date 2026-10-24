package sh.hutch.postmaster.services

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.InjectWireMock
import sh.hutch.postmaster.testsupport.IntegrationProfile
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.WireMockTestResource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fetching pages with headless Chromium and files with curl (REQ Technical
 * choices: "curl and a headless browser that simulates Chrome running on
 * Windows in the user agent"; fetch_link_contents; process bill (download the
 * bill); CLAR D15), with the fake Chromium and real curl against WireMock.
 */
@QuarkusTest
@TestProfile(IntegrationProfile::class)
@WithTestResource(WireMockTestResource::class)
class HttpFetcherTest {
  @field:InjectWireMock
  lateinit var wireMock: WireMockServer

  @Inject
  lateinit var fetcher: HttpFetcher

  @Inject
  lateinit var settings: SettingsService

  @TempDir
  lateinit var dir: Path

  @BeforeEach
  fun reset() {
    wireMock.resetAll()
    FakeTools.reset()
  }

  @AfterEach
  fun restore() {
    settings.update("fetch.timeout", "30s")
  }

  @Test
  fun `a page is fetched with headless Chromium using the Chrome-on-Windows user agent`() {
    wireMock.stubFor(get(urlEqualTo("/newsletter")).willReturn(aResponse().withBody("<html><body>Newsletter 42</body></html>")))
    val agent = settings.getString("fetch.user_agent")!!

    val page = fetcher.fetchPage(wireMock.baseUrl() + "/newsletter")

    assertTrue(page.contains("Newsletter 42"), page)
    val args = FakeTools.chromiumCalls().single().args
    assertTrue("--dump-dom" in args, args.toString())
    assertTrue(args.any { it.startsWith("--headless") }, args.toString())
    assertTrue("--user-agent=$agent" in args, args.toString())
    assertTrue(agent.contains("Windows NT") && agent.contains("Chrome/"), agent)
    assertEquals(wireMock.baseUrl() + "/newsletter", args.last())
  }

  @Test
  fun `a browser failure is reported as a fetch error`() {
    FakeTools.failChromium("chromium crashed")

    val error = assertThrows<FetchException> { fetcher.fetchPage(wireMock.baseUrl() + "/x") }
    assertTrue(error.message!!.isNotBlank())
  }

  @Test
  fun `only web addresses are fetched`() {
    for (url in listOf("file:///etc/passwd", "ftp://example.test/x", "javascript:alert(1)")) {
      assertThrows<FetchException> { fetcher.fetchPage(url) }
      assertThrows<FetchException> { fetcher.download(url, dir.resolve("x")) }
    }
    assertTrue(FakeTools.chromiumCalls().isEmpty(), "nothing was run")
  }

  @Test
  fun `a page that takes too long is abandoned at the fetch timeout`() {
    // CLAR D9 (fetch.timeout)
    settings.update("fetch.timeout", "2s")
    wireMock.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withBody("late").withFixedDelay(8000)))

    val started = System.nanoTime()
    assertThrows<FetchException> { fetcher.fetchPage(wireMock.baseUrl() + "/slow") }
    assertTrue((System.nanoTime() - started) / 1e9 < 6, "stopped near the timeout")
  }

  @Test
  fun `a file is downloaded with curl, keeping its bytes and content type`() {
    wireMock.stubFor(
        get(urlEqualTo("/bill.pdf")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody(TestMessages.PDF))
    )
    val agent = settings.getString("fetch.user_agent")!!
    val target = dir.resolve("2026/10/bill.pdf")

    val file = fetcher.download(wireMock.baseUrl() + "/bill.pdf", target)

    assertArrayEquals(TestMessages.PDF, Files.readAllBytes(target))
    assertEquals("application/pdf", file.contentType)
    assertEquals(TestMessages.PDF.size.toLong(), file.size)
    wireMock.verify(getRequestedFor(urlEqualTo("/bill.pdf")).withHeader("User-Agent", equalTo(agent)))
  }

  @Test
  fun `a failed download is reported`() {
    wireMock.stubFor(get(urlEqualTo("/missing.pdf")).willReturn(aResponse().withStatus(404)))

    assertThrows<FetchException> { fetcher.download(wireMock.baseUrl() + "/missing.pdf", dir.resolve("m.pdf")) }
  }
}
