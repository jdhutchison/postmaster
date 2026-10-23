package sh.hutch.postmaster.controllers

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.testsupport.AdminProfile
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.none
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts
import sh.hutch.postmaster.testsupport.triggers

// REQ Types 1-8; CLAR D2, Q16, D20
@QuarkusTest
@TestProfile(AdminProfile::class)
class TypesPageTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var types: TypeRepository

  @Test
  fun `the page lists every active type with edit and delete icons`() {
    // REQ Types 1, 2
    val page = getHtml("/types")
    val ebay = types.findActiveByName("Ebay")!!

    val row = page.testId("type-row-${ebay.id}")
    assertTrue(row.text().contains("Ebay business"))
    assertEquals("Edit Ebay", row.select("[data-testid=edit-type-${ebay.id}]").attr("aria-label"))
    assertEquals("Delete Ebay", row.select("[data-testid=delete-type-${ebay.id}]").attr("aria-label"))
    assertTrue(page.text().contains("Bill"))
    page.none("[data-testid^=type-row-]:contains(Retired type)")
    assertEquals(1, page.select("[data-testid=new-type]").size, "REQ Types 6")
  }

  @Test
  fun `the Bill type is marked as a system type and can't be deleted`() {
    // CLAR Q16
    val bill = types.findBill()!!
    val row = getHtml("/types").testId("type-row-${bill.id}")

    assertEquals("System", row.select("[data-testid=system-badge]").text())
    row.none("[data-testid=delete-type-${bill.id}]")
    assertEquals(409, htmx().delete("/types/${bill.id}").statusCode)
  }

  @Test
  fun `deleting asks for confirmation`() {
    // REQ Types 4
    val ebay = types.findActiveByName("Ebay")!!
    val button = getHtml("/types").testId("delete-type-${ebay.id}")

    assertEquals("Delete type Ebay?", button.attr("hx-confirm"))
  }

  @Test
  fun `the new type form has a colour picker and hex field`() {
    // REQ Types 7, 8
    val response = htmx().get("/types/new")
    val form = parseHtml(response.asString())

    assertEquals("color", form.testId("colour-picker").attr("type"))
    assertEquals("#6272a4", form.testId("colour-hex").attr("value"))
    assertEquals("colour", form.testId("colour-hex").attr("name"))
    assertEquals(mapOf("id" to "type-dialog"), response.triggers("HX-Trigger-After-Swap")["pm-dialog-open"])
  }

  @Test
  fun `a type is created, edited and soft-deleted`() {
    // REQ Types 3, 5, 7
    val created = htmx().formParam("name", "Sport").formParam("description", "Club sport").formParam("colour",
        "#FF79C6")
        .post("/types")
    assertEquals(200, created.statusCode)
    assertEquals(listOf("success" to "Created type Sport"), created.toasts())
    assertEquals(mapOf("id" to "type-dialog"), created.triggers()["pm-dialog-close"])
    val sport = types.findActiveByName("Sport")!!
    assertEquals("#ff79c6", sport.colour)

    val saved = htmx().formParam("name", "Sports").formParam("description", "").formParam("colour", "#ff79c6")
        .put("/types/${sport.id}")
    assertEquals(listOf("success" to "Saved type Sports"), saved.toasts())

    val deleted = htmx().delete("/types/${sport.id}")
    assertEquals(listOf("success" to "Deleted type Sports"), deleted.toasts())
    assertEquals(null, types.findActiveByName("Sports"))
    assertEquals(true, dataSource.row("SELECT deleted FROM Types WHERE id = ?", sport.id)["deleted"], "REQ Types 5")
  }

  @Test
  fun `a name that differs only in case from another type is refused`() {
    // CLAR D20
    val refused = htmx().formParam("name", "payslip").formParam("colour", "#123456").post("/types")

    assertEquals(422, refused.statusCode)
    val form = parseHtml(refused.asString())
    assertTrue(form.testId("error-name").text().isNotBlank())
    assertEquals("true", form.select("[name=name]").attr("aria-invalid"))
    assertEquals("error", refused.toasts().single().first)
  }

  @Test
  fun `a bad colour is refused`() {
    val refused = htmx().formParam("name", "Odd colour").formParam("colour", "blue").post("/types")

    assertEquals(422, refused.statusCode)
    assertTrue(parseHtml(refused.asString()).testId("error-colour").text().isNotBlank())
  }

  @Test
  fun `unknown types are not found`() {
    assertEquals(404, htmx().get("/types/999999/edit").statusCode)
    assertEquals(404, htmx().delete("/types/999999").statusCode)
  }
}
