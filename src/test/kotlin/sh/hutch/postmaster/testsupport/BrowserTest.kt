package sh.hutch.postmaster.testsupport

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import io.quarkus.test.common.http.TestHTTPResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI
import java.net.URL
import java.util.Collections

/**
 * Base class for Playwright browser tests of the UI. Subclasses are
 * `@QuarkusTest` classes. One headless Chromium is shared by every browser
 * test in the JVM; each test gets fresh contexts and pages.
 *
 * Every page's requests are recorded. Requests that leave the application
 * (another host, a CDN, a font service) are blocked, and the test fails
 * afterwards if there were any: everything the UI needs must be served
 * locally (REQ User interface style; CLAR S4).
 */
abstract class BrowserTest {
  @TestHTTPResource("/")
  lateinit var baseUrl: URL

  private val contexts = mutableListOf<BrowserContext>()

  /** URLs of requests that went outside the application, in the order they were made */
  val externalRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

  /** URLs of every request made by pages in this test */
  val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

  /**
   * Opens a new page in its own context.
   *
   * @param colorScheme the system colour scheme the page sees
   * @param width the viewport width; 360 or 390 is a phone, 1280 a desktop
   */
  fun openPage(
      colorScheme: ColorScheme = ColorScheme.LIGHT,
      width: Int = 1280,
      height: Int = 800
  ): Page {
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setColorScheme(colorScheme)
            .setViewportSize(width, height)
            .setBaseURL(baseUrl.toString().removeSuffix("/") + "/")
    )
    contexts += context
    val origin = origin(baseUrl.toString())
    context.onRequest { request ->
      val url = request.url()
      requests += url
      if (!isLocal(url, origin)) {
        externalRequests += url
      }
    }
    context.route("**/*") { route ->
      if (isLocal(route.request().url(), origin)) route.resume() else route.abort()
    }
    return context.newPage()
  }

  /** Opens [path], relative to the application's root path, in a new desktop light-mode page */
  fun visit(path: String): Page {
    val page = openPage()
    page.navigate(path.removePrefix("/"))
    return page
  }

  @AfterEach
  fun checkNoExternalRequests() {
    try {
      assertTrue(externalRequests.isEmpty(), "requests left the application: $externalRequests")
    } finally {
      contexts.forEach { it.close() }
      contexts.clear()
      externalRequests.clear()
      requests.clear()
    }
  }

  companion object {
    private val playwright: Playwright by lazy {
      Playwright.create().also { created ->
        Runtime.getRuntime().addShutdownHook(Thread { created.close() })
      }
    }

    val browser: Browser by lazy {
      playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }

    private fun origin(url: String): String {
      val uri = URI(url)
      return "${uri.scheme}://${uri.authority}"
    }

    private fun isLocal(url: String, origin: String): Boolean =
        url.startsWith("$origin/") || url == origin || url.startsWith("data:") || url.startsWith("blob:") ||
            url.startsWith("about:")
  }
}
