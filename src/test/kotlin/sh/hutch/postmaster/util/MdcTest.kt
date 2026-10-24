package sh.hutch.postmaster.util

import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The logging context helper (CLAR R3): keys are set for a block, then put back as they were. */
class MdcTest {
  @AfterEach
  fun clear() {
    MDC.clear()
  }

  @Test
  fun `keys are set for the block and removed afterwards`() {
    val inside = Mdc.with(Mdc.EMAIL to 42, Mdc.PHASE to "llm") { MDC.get(Mdc.EMAIL) to MDC.get(Mdc.PHASE) }

    assertEquals("42" to "llm", inside)
    assertNull(MDC.get(Mdc.EMAIL))
    assertNull(MDC.get(Mdc.PHASE))
  }

  @Test
  fun `nested blocks put back the outer values`() {
    Mdc.with(Mdc.RULE to "outer") {
      Mdc.with(Mdc.RULE to "inner", Mdc.ACTION to "move") {
        assertEquals("inner", MDC.get(Mdc.RULE))
        assertEquals("move", MDC.get(Mdc.ACTION))
      }
      assertEquals("outer", MDC.get(Mdc.RULE))
      assertNull(MDC.get(Mdc.ACTION))
    }
    assertNull(MDC.get(Mdc.RULE))
  }

  @Test
  fun `a null value removes the key for the block`() {
    MDC.put(Mdc.MESSAGE_ID, "a@b")

    Mdc.with(Mdc.MESSAGE_ID to null) { assertNull(MDC.get(Mdc.MESSAGE_ID)) }

    assertEquals("a@b", MDC.get(Mdc.MESSAGE_ID))
  }

  @Test
  fun `keys are removed even when the block throws`() {
    assertThrows<IllegalStateException> { Mdc.with(Mdc.RUN to 7) { throw IllegalStateException("boom") } }

    assertNull(MDC.get(Mdc.RUN))
  }
}
