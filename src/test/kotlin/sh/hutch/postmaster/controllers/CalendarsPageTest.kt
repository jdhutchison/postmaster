package sh.hutch.postmaster.controllers

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.testsupport.AdminProfile
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts

// REQ Calendars 1-7; CLAR D4 (names from the khal config), D20
@QuarkusTest
@TestProfile(AdminProfile::class)
class CalendarsPageTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var calendars: CalendarRepository

  @Test
  fun `the page lists the calendars with name, colour and an enabled switch`() {
    // REQ Calendars 1-3
    val page = getHtml("/calendars")
    val football = calendars.findActiveByName("Football")!!
    val family = calendars.findActiveByName("Family-and-Kids")!!

    val row = page.testId("calendar-row-${family.id}")
    assertTrue(row.text().contains("Family-and-Kids"))
    assertTrue(row.text().contains("#50fa7b"), "the colour is shown")
    assertEquals("checkbox", row.select("[data-testid=enabled-calendar-${family.id}]").attr("type"))
    assertTrue(row.select("[data-testid=enabled-calendar-${family.id}]").hasAttr("checked"))
    assertTrue(!page.testId("enabled-calendar-${football.id}").hasAttr("checked"),
        "the disabled calendar's switch is off")
    assertTrue(page.select("[data-testid^=calendar-row-]").none { it.text().contains("Retired calendar") })
    assertEquals(1, row.select("[data-testid=edit-calendar-${family.id}]").size)
    assertEquals("Delete calendar Family-and-Kids?", row.select("[data-testid=delete-calendar-${family.id}]")
        .attr("hx-confirm"))
  }

  @Test
  fun `the new calendar form offers the khal calendars`() {
    // CLAR D4
    val form = parseHtml(htmx().get("/calendars/new").asString())

    val options = form.select("select[name=name] option").map { it.attr("value") }.filter { it.isNotEmpty() }
    assertTrue(options.containsAll(listOf("Dinners")), "khal calendars offered: $options")
    assertTrue(options.all { it in FakeTools.CALENDARS }, "only khal calendars: $options")
    assertEquals("e.g. 4h, 1d", form.select("input[name=notificationPeriods]").attr("placeholder"))
  }

  @Test
  fun `a calendar is created, disabled, enabled and deleted`() {
    val created = htmx().formParam("name", "Dinners").formParam("description", "Dinner plans")
        .formParam("colour", "#FFB86C").formParam("notificationPeriods", "2h").formParam("enabled", "true")
        .post("/calendars")
    assertEquals(200, created.statusCode, created.asString())
    assertEquals(listOf("success" to "Created calendar Dinners"), created.toasts())
    val dinners = calendars.findActiveByName("Dinners")!!

    assertEquals(listOf("success" to "Disabled calendar Dinners"), htmx().formParam("enabled", "false")
        .post("/calendars/${dinners.id}/enabled").toasts())
    assertEquals(false, dataSource.row("SELECT enabled FROM Calendars WHERE id = ?", dinners.id)["enabled"])
    assertEquals(listOf("success" to "Enabled calendar Dinners"), htmx().formParam("enabled", "true")
        .post("/calendars/${dinners.id}/enabled").toasts())

    assertEquals(listOf("success" to "Deleted calendar Dinners"), htmx().delete("/calendars/${dinners.id}").toasts())
    assertEquals(null, calendars.findActiveByName("Dinners"))
  }

  @Test
  fun `invalid durations, a missing description and a duplicate name are refused`() {
    // REQ Calendars 5, 7; CLAR D20
    val refused = htmx().formParam("name", "Football").formParam("description", "")
        .formParam("colour", "#123456").formParam("notificationPeriods", "4h,1d").post("/calendars")

    assertEquals(422, refused.statusCode)
    val form = parseHtml(refused.asString())
    for (field in listOf("name", "description", "notificationPeriods")) {
      assertTrue(form.select("[data-testid=error-$field]").text().isNotBlank(), "$field error shown")
    }
  }
}
