package sh.hutch.postmaster.testsupport

import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification

/** A request as HTMX sends it */
fun htmx(): RequestSpecification = given().header("HX-Request", "true").redirects().follow(false)

/** The toasts an HTMX response raises in its HX-Trigger header, as level to message */
fun Response.toasts(): List<Pair<String, String>> {
  val header = header("HX-Trigger") ?: return emptyList()
  val toasts = ObjectMapper().readTree(header)["pm-toast"]?.get("toasts") ?: return emptyList()
  return toasts.map { it["level"].asText() to it["message"].asText() }
}

/** The other events in an HX-Trigger header, by name */
fun Response.triggers(name: String = "HX-Trigger"): Map<String, Any?> {
  val header = header(name) ?: return emptyMap()
  @Suppress("UNCHECKED_CAST")
  return ObjectMapper().readValue(header, Map::class.java) as Map<String, Any?>
}

/** The flash toasts a redirect sets, read by following it */
fun Response.flashMessages(): List<String> {
  val flash = cookie("pm_flash") ?: return emptyList()
  val page = parseHtml(given().cookie("pm_flash", flash).get(header("Location").removePrefix("/mail")).asString())
  return page.select("[data-testid=toast-message]").map { it.text() }
}
