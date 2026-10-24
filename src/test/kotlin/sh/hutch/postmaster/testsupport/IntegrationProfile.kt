package sh.hutch.postmaster.testsupport

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * The known data set in an application of its own, for the integration
 * helper tests (PLAN Phase 4). They point settings at GreenMail and WireMock,
 * so they're kept away from tests that check the seeded settings.
 */
class IntegrationProfile : QuarkusTestProfile {
  override fun getConfigOverrides(): Map<String, String> =
      mapOf("quarkus.flyway.locations" to "db/migration,db/testdata")
}
