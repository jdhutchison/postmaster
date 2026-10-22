package sh.hutch.postmaster.testsupport

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import io.quarkus.test.common.http.TestHTTPResource
import org.junit.jupiter.api.AfterEach
import java.net.URL

/**
 * Base class for Playwright browser tests of the UI. Subclasses are
 * `@QuarkusTest` classes. One headless Chromium is shared by every browser
 * test in the JVM; each test gets a fresh context and page.
 */
abstract class BrowserTest {
  @TestHTTPResource("/")
  lateinit var baseUrl: URL

  private val contexts = mutableListOf<BrowserContext>()

  /**
   * Opens a new page in its own context.
   *
   * @param colorScheme the system colour scheme the page sees
   * @param width the viewport width; 390 is a phone, 1280 a desktop
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
    return context.newPage()
  }

  /** Opens [path], relative to the application's root path, in a new desktop light-mode page */
  fun visit(path: String): Page {
    val page = openPage()
    page.navigate(path.removePrefix("/"))
    return page
  }

  @AfterEach
  fun closeContexts() {
    contexts.forEach { it.close() }
    contexts.clear()
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
  }
}
