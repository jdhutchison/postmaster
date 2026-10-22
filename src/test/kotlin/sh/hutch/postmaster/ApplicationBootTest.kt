package sh.hutch.postmaster

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// PLAN 0.4 smoke test: the application boots against the in-memory test
// database (REQ Technical choices: tests use an in-memory database) and serves
// HTTP under its root path, /mail (CLAR S4).
@QuarkusTest
class ApplicationBootTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Test
  fun `connects to the in-memory H2 database`() {
    dataSource.connection.use { connection ->
      assertEquals("H2", connection.metaData.databaseProductName)
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 1").use { rows ->
          rows.next()
          assertEquals(1, rows.getInt(1))
        }
      }
    }
  }

  @Test
  fun `serves HTTP under the mail root path`() {
    given().basePath("")
        .get("/mail/q/openapi")
        .then()
        .statusCode(200)
        .body(containsString("openapi:"))
  }
}
