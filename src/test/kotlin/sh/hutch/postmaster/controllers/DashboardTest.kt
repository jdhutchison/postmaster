package sh.hutch.postmaster.controllers

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.processing.PipelineTest
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts

/** The dashboard (PLAN.md 6.3): runs, the next run, counts and "Run now". */
@QuarkusTest
class DashboardTest : PipelineTest() {

  @Test
  fun `run now starts processing in the background, and the dashboard follows it`() {
    llm(SUMMARISE, """{"summary": "Slow", "response_required": true}""", delayMs = 2000)
    inbox(message("<dash@mail.example>", "Dashboard"))

    val started = htmx().post("/runs")
    assertEquals(200, started.statusCode)
    assertEquals(listOf("success" to "Processing started"), started.toasts())
    val fragment = parseHtml(started.asString()).testId("home-page")
    assertEquals("true", fragment.attr("data-running"))
    assertEquals("every 2s", fragment.attr("hx-trigger"), "it polls while running")
    assertTrue(fragment.testId("run-now").hasAttr("disabled"))

    val again = htmx().post("/runs")
    assertEquals(409, again.statusCode)
    assertEquals("error", again.toasts().single().first)

    val deadline = System.currentTimeMillis() + 20_000
    while (processing.isRunning && System.currentTimeMillis() < deadline) {
      Thread.sleep(200)
    }
    val page = getHtml("/")
    val dashboard = page.testId("home-page")
    assertFalse(dashboard.hasAttr("hx-trigger"), "polling stops once the run is done")
    assertEquals("Last run succeeded", page.testId("run-state").text())
    assertTrue(page.testId("count-response").text().contains(Regex("[1-9]")))
    assertEquals("/mail/emails?response=true", page.testId("count-response").attr("href"))
    assertTrue(page.select("[data-testid^=run-row-]").isNotEmpty())
    assertTrue(page.testId("next-run").text().contains("Scheduled processing is off"), "the tests turn the scheduler off")
  }
}
