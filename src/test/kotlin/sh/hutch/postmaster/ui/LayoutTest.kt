package sh.hutch.postmaster.ui

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.one
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.texts

/**
 * The server-rendered base layout (PLAN 2.1; REQ Technical choices "The user
 * interface is to be statically rendered. HTMX can be used"; REQ User
 * interface style), checked in the HTML.
 */
@QuarkusTest
class LayoutTest {
  @Test
  fun `a page is rendered inside the layout with its title`() {
    val page = getHtml("/test-fixtures/ui")

    assertEquals("UI fixture · Postmaster", page.title())
    assertEquals("UI fixture", page.testId("page-title").text())
    assertEquals("h1", page.testId("page-title").tagName())
    assertTrue(page.one("html").attr("lang").isNotBlank(), "the page declares its language")
  }

  @Test
  fun `pages are set up for phone screens`() {
    // REQ User interface style: "work as well as possible on a mobile screen"
    val viewport = getHtml("/test-fixtures/ui").one("meta[name=viewport]").attr("content")

    assertTrue(viewport.contains("width=device-width"), viewport)
  }

  @Test
  fun `all menu options are in one menu, in order`() {
    // REQ User interface style: "All menu options must be in a single menu";
    // the pages are those in REQ Configuration, Types, Calendars, Prompts,
    // Rules and Viewing emails, plus the dashboard (CLAR Q20)
    val page = getHtml("/test-fixtures/ui")

    assertEquals(1, page.select("nav").size, "one navigation element")
    val menu = page.testId("menu")
    assertEquals("nav", menu.tagName())
    assertEquals(
        listOf(
            "Dashboard" to "/mail/", "Emails" to "/mail/emails", "Types" to "/mail/types",
            "Calendars" to "/mail/calendars", "Prompts" to "/mail/prompts",
            "Classification rules" to "/mail/rules/classification", "Action rules" to "/mail/rules/action",
            "Configuration" to "/mail/configuration"
        ),
        menu.select("li > a").map { it.text().trim() to it.attr("href") }
    )
    assertEquals(1, page.select("[data-testid=menu-button]").size)
  }

  @Test
  fun `no links outside the menu lead to other pages`() {
    // REQ User interface style: all menu options in a single menu. Outside
    // the page content, the only other link is the skip link.
    val page = getHtml("/test-fixtures/ui")

    val chromeLinks = page.select("body a[href]").filter { link ->
      link.parents().none { it.id() == "pm-menu" || it.id() == "pm-main" }
    }
    assertEquals(listOf("#pm-main"), chromeLinks.map { it.attr("href") })
  }

  @Test
  fun `the menu marks the current page`() {
    val page = getHtml("/test-fixtures/ui")

    assertEquals(listOf("Types"), page.select("#pm-menu [aria-current=page]").map { it.text().trim() })
  }

  @Test
  fun `the home page uses the layout and is the current page in the menu`() {
    val page = getHtml("/")

    assertEquals(1, page.select("[data-testid=home-page]").size)
    assertEquals(listOf("Dashboard"), page.select("#pm-menu [aria-current=page]").map { it.text().trim() })
  }

  @Test
  fun `every script, stylesheet and font comes from the application`() {
    // REQ User interface style, Technical choices (HTMX); CLAR S4 (bundled, no CDN)
    val page = getHtml("/test-fixtures/ui")

    val urls = page.select("script[src]").map { it.attr("src") } +
        page.select("link[href]").map { it.attr("href") } +
        page.select("img[src]").map { it.attr("src") }
    assertTrue(urls.isNotEmpty())
    for (url in urls) {
      assertTrue(url.startsWith("/mail/"), "$url is served by the application")
    }
    assertTrue(urls.any { it.startsWith("/mail/assets/vendor/htmx/htmx.min.js") }, "HTMX is bundled: $urls")
    assertTrue(urls.any { it.startsWith("/mail/assets/css/postmaster.css") }, urls.toString())
    assertTrue(urls.any { it.startsWith("/mail/assets/js/postmaster.js") }, urls.toString())
  }

  @Test
  fun `the bundled assets are served`() {
    for (path in listOf(
        "/assets/vendor/htmx/htmx.min.js", "/assets/css/postmaster.css", "/assets/js/postmaster.js",
        "/assets/fonts/atkinson-hyperlegible-next/atkinson-hyperlegible-next-latin-wght-normal.woff2"
    )) {
      given().get(path).then().statusCode(200)
    }
  }

  @Test
  fun `the bundled font and HTMX keep their licences`() {
    // The font is under the SIL OFL, which requires the licence to travel with it
    given().get("/assets/fonts/atkinson-hyperlegible-next/OFL.txt").then().statusCode(200)
    given().get("/assets/vendor/htmx/LICENSE").then().statusCode(200)
  }

  @Test
  fun `a content security policy stops the browser loading anything from elsewhere`() {
    // CLAR S4: everything is served by the application
    val policy = given().get("/test-fixtures/ui").header("Content-Security-Policy")

    assertTrue(policy != null && policy.contains("default-src 'self'"), "policy: $policy")
    assertFalse(policy!!.contains("http:") || policy.contains("https:"), "no other origins: $policy")
  }

  @Test
  fun `there is a skip link to the content`() {
    val page = getHtml("/test-fixtures/ui")

    assertEquals("#pm-main", page.one("a.pm-skip-link").attr("href"))
    assertEquals(listOf("main"), page.select("#pm-main").map { it.tagName() })
    assertTrue(page.texts("#pm-main [data-testid=page-title]").isNotEmpty())
  }
}
