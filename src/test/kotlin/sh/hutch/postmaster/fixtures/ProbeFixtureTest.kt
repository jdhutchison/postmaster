package sh.hutch.postmaster.fixtures

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.testId

// The UI fixture pages work: test-only resources and templates are served
// under the root path (PLAN 2.3)
@QuarkusTest
class ProbeFixtureTest {
  @Test
  fun `a test-only resource renders a test-only template`() {
    assertEquals("fixture served", getHtml("/test-fixtures/probe").testId("probe").text())
  }
}
