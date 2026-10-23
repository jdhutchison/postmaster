package sh.hutch.postmaster.ui

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.controllers.Toast
import sh.hutch.postmaster.controllers.Toasts
import sh.hutch.postmaster.testsupport.parseHtml

/**
 * How toasts reach the page (REQ User interface style, toasts; contract in
 * docs/dev/contracts.md): rendered with a full page, carried across a
 * redirect in a short-lived cookie, or raised by an HTMX response header.
 */
@QuarkusTest
class ToastsTest {
  @Test
  fun `toasts added while handling a page are rendered with it`() {
    val page = parseHtml(given().get("/test-fixtures/ui?toast=info&message=Hello").body.asString())

    val toast = page.select("[data-testid=toast]")
    assertEquals(1, toast.size)
    assertEquals("info", toast.attr("data-level"))
    assertEquals("Hello", toast.select("[data-testid=toast-message]").text())
    assertEquals(1, toast.select("[data-testid=toast-close]").size, "every toast can be dismissed")
  }

  @Test
  fun `a redirect carries its toasts in a short-lived cookie that is used once`() {
    val redirect = given().redirects().follow(false).post("/test-fixtures/ui/form-action")

    assertEquals(303, redirect.statusCode)
    assertTrue(redirect.header("Location").endsWith("/mail/test-fixtures/ui"), redirect.header("Location"))
    val setCookie = redirect.headers.getValues("Set-Cookie").single { it.startsWith("pm_flash=") }
    for (attribute in listOf("Path=/mail", "HttpOnly", "SameSite=Lax", "Max-Age=60")) {
      assertTrue(setCookie.contains(attribute, ignoreCase = true), "$attribute in: $setCookie")
    }

    val flash = redirect.cookie("pm_flash")
    val next = given().cookie("pm_flash", flash).get("/test-fixtures/ui")
    val page = parseHtml(next.body.asString())
    assertEquals(listOf("Form submitted"), page.select("[data-testid=toast-message]").map { it.text() })
    assertEquals("success", page.select("[data-testid=toast]").attr("data-level"))
    val cleared = next.headers.getValues("Set-Cookie").single { it.startsWith("pm_flash=") }
    assertTrue(cleared.contains("Max-Age=0", ignoreCase = true), "the cookie is cleared: $cleared")
  }

  @Test
  fun `the HTMX trigger header carries the toasts as JSON`() {
    val header = Toasts.hxTrigger(Toast.success("Saved"), Toast.error("Not saved"))

    assertEquals("HX-Trigger", Toasts.HEADER)
    val toasts = ObjectMapper().readTree(header)["pm-toast"]["toasts"]
    assertEquals(
        listOf("success" to "Saved", "error" to "Not saved"),
        toasts.map { it["level"].asText() to it["message"].asText() }
    )
  }

  @Test
  fun `the HTMX trigger header stays ASCII`() {
    val header = Toasts.hxTrigger(Toast.success("Gespeichert ✓ – “Rechnung”"))

    assertTrue(header.all { it.code < 128 }, header)
    assertEquals(
        "Gespeichert ✓ – “Rechnung”",
        ObjectMapper().readTree(header)["pm-toast"]["toasts"][0]["message"].asText()
    )
  }

  @Test
  fun `an HTMX response raises its toast through the trigger header`() {
    val response = given().header("HX-Request", "true").post("/test-fixtures/ui/action")

    val toasts = ObjectMapper().readTree(response.header("HX-Trigger"))["pm-toast"]["toasts"]
    assertEquals("Action done", toasts[0]["message"].asText())
  }
}
