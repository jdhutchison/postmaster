package sh.hutch.postmaster.testsupport

import io.quarkus.test.common.TestResourceScope
import io.quarkus.test.common.WithTestResource

/**
 * Test resources every Quarkus test needs. The startup config requires a khal
 * config path, so the fake khal setup is global.
 */
@WithTestResource(KhalTestResource::class, scope = TestResourceScope.GLOBAL)
class GlobalTestResources
