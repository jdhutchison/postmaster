package sh.hutch.postmaster.testsupport

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * The known data set in an application and database of its own, for the
 * admin page tests. Their changes over HTTP are committed, so they're kept
 * away from tests that count the known data.
 */
class AdminProfile : QuarkusTestProfile {
  override fun getConfigOverrides(): Map<String, String> =
      mapOf("quarkus.flyway.locations" to "db/migration,db/testdata")
}
