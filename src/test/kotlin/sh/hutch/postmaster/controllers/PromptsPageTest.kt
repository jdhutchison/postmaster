package sh.hutch.postmaster.controllers

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.Placeholders
import sh.hutch.postmaster.repositories.PromptRepository
import sh.hutch.postmaster.testsupport.AdminProfile
import sh.hutch.postmaster.testsupport.flashMessages
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.none
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts

// REQ Prompts 1-5 and Editing or creating a prompt 1-4; CLAR Q15, Q19
@QuarkusTest
@TestProfile(AdminProfile::class)
class PromptsPageTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var prompts: PromptRepository

  @Test
  fun `the list shows every prompt, and only user prompts can be deleted`() {
    // REQ Prompts 1, 2, 3; CLAR Q15
    val page = getHtml("/prompts")
    val summarise = prompts.findByKey("summarise")!!
    val mine = prompts.findActiveByName("Translate to English")!!

    assertEquals(1, page.select("[data-testid=new-prompt]").size)
    val systemRow = page.testId("prompt-row-${summarise.id}")
    assertEquals(1, systemRow.select("[data-testid=edit-prompt-${summarise.id}]").size)
    systemRow.none("[data-testid=delete-prompt-${summarise.id}]")
    assertEquals(
        "Delete prompt Translate to English?",
        page.testId("delete-prompt-${mine.id}").attr("hx-confirm"),
        "REQ Prompts 4"
    )
    assertEquals(409, htmx().delete("/prompts/${summarise.id}").statusCode)
  }

  @Test
  fun `the editor has the name, the text and a placeholder for each item in the requirements`() {
    // REQ Editing or creating a prompt 4
    val editor = getHtml("/prompts/new")

    assertEquals(1, editor.select("form[data-testid=prompt-form] input[name=name]").size)
    assertEquals("textarea", editor.testId("prompt-contents").tagName())
    assertEquals(
        Placeholders.ALL.map { it.token },
        editor.testId("placeholder-select").select("option").map { it.attr("value") }
    )
  }

  @Test
  fun `a prompt is created and edited, with a toast after each`() {
    val created = given().redirects().follow(false).formParam("name", "Shopping list").formParam("contents",
        "List {{text}}")
        .post("/prompts")
    assertEquals(303, created.statusCode)
    assertEquals(listOf("Created prompt Shopping list"), created.flashMessages())
    val prompt = prompts.findActiveByName("Shopping list")!!

    val saved = given().redirects().follow(false).formParam("name", "Shopping").formParam("contents",
        "List {{subject}}")
        .post("/prompts/${prompt.id}")
    assertEquals(listOf("Saved prompt Shopping"), saved.flashMessages())
    assertEquals("List {{subject}}", dataSource.row("SELECT contents FROM Prompts WHERE id = ?", prompt.id)["contents"])

    assertEquals(listOf("success" to "Deleted prompt Shopping"), htmx().delete("/prompts/${prompt.id}").toasts())
  }

  @Test
  fun `the name and text are required`() {
    // REQ Editing or creating a prompt 1
    val refused = given().redirects().follow(false).formParam("name", "").formParam("contents", " ").post("/prompts")

    assertEquals(422, refused.statusCode)
    val editor = parseHtml(refused.asString())
    assertTrue(editor.testId("error-name").text().isNotBlank())
    assertTrue(editor.testId("error-contents").text().isNotBlank())
  }

  @Test
  fun `a system prompt shows its key, read-only`() {
    val summarise = prompts.findByKey("summarise")!!

    assertEquals("summarise", getHtml("/prompts/${summarise.id}/edit").testId("prompt-key").text().trim())
  }

  @Test
  fun `the preview renders markdown`() {
    // CLAR Q19
    val html = htmx().formParam("contents", "**Bold** text").post("/prompts/preview").asString()

    assertTrue(html.contains("<strong>Bold</strong>"), html)
  }
}
