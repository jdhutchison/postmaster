package sh.hutch.postmaster.controllers

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Cross-site request forgery: the pages sit behind basic auth, which browsers
 * send with requests from any site, so changes are refused unless the
 * browser says the request came from Postmaster's own pages.
 */
@QuarkusTest
class CrossSiteRequestFilterTest {
  private fun post(site: String?) =
      given().header("HX-Request", "true").redirects().follow(false)
          .apply { if (site != null) header("Sec-Fetch-Site", site) }
          .formParam("name", "").formParam("colour", "#50fa7b")
          .post("/types")

  @Test
  fun `changes from another site are refused`() {
    assertEquals(403, post("cross-site").statusCode)
    assertEquals(403, post("same-site").statusCode)
  }

  @Test
  fun `changes from Postmaster's own pages, or from clients that don't say, go through`() {
    // An empty name is invalid, so a request that gets through is answered with 422
    assertEquals(422, post("same-origin").statusCode)
    assertEquals(422, post(null).statusCode)
  }

  @Test
  fun `reading is allowed from anywhere`() {
    val response = given().header("Sec-Fetch-Site", "cross-site").get("/types")

    assertNotEquals(403, response.statusCode)
  }
}
