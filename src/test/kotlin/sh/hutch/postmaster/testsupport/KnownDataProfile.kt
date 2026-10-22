package sh.hutch.postmaster.testsupport

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Loads the known data set (src/test/resources/db/testdata) on top of the
 * production migrations and seeds. Tests using this profile share one
 * application and database, so tests that change data must use
 * `@TestTransaction` or undo their changes.
 */
class KnownDataProfile : QuarkusTestProfile {
  override fun getConfigOverrides(): Map<String, String> =
      mapOf("quarkus.flyway.locations" to "db/migration,db/testdata")
}
