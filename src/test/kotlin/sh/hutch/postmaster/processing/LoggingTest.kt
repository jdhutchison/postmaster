package sh.hutch.postmaster.processing

import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.MDC
import org.jboss.logmanager.ExtLogRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.htmx
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Logging (CLAR R3): plain text to stdout in every profile, with the run,
 * email, phase, rule, action and source in the MDC while that work goes on,
 * and removed when it ends.
 */
@QuarkusTest
class LoggingTest : PipelineTest() {
  /** A log line and the MDC it was logged with */
  data class Line(val logger: String, val message: String, val mdc: Map<String, String>)

  private val lines = CopyOnWriteArrayList<Line>()

  private val capture = object : Handler() {
    override fun publish(record: LogRecord) {
      if (record is ExtLogRecord && record.loggerName.startsWith("sh.hutch.postmaster")) {
        lines += Line(record.loggerName, record.formattedMessage ?: "", record.mdcCopy)
      }
    }

    override fun flush() {}

    override fun close() {}
  }

  @BeforeEach
  fun startCapture() {
    Logger.getLogger("").addHandler(capture)
  }

  @AfterEach
  fun stopCapture() {
    Logger.getLogger("").removeHandler(capture)
  }

  private fun line(text: String): Line = lines.firstOrNull { it.message.startsWith(text) } ?: throw AssertionError("no '$text' line in $lines")

  @Test
  fun `processing logs carry the run, email, phase, rule and action`() {
    rule(RuleType.CLASSIFICATION, "log-school", listOf(condition("subject", "contains", "Logged")), listOf(action("set_reply_needed")))
    rule(RuleType.ACTION, "log-read", listOf(condition("subject", "contains", "Logged")), listOf(action("mark_read")))
    inbox(message("<logging@mail.example>", "Logged email"))

    val run = processing.runNow(RunTrigger.MANUAL)!!

    val runId = run.id.toString()
    val emailId = email("logging@mail.example")["id"].toString()
    assertEquals(mapOf("run" to runId), line("Processing run started").mdc)
    val processingLine = line("Processing 'Logged email'")
    assertEquals(mapOf("run" to runId, "email" to emailId, "message_id" to "logging@mail.example"), processingLine.mdc)
    assertEquals(
        mapOf("run" to runId, "email" to emailId, "message_id" to "logging@mail.example", "phase" to "classification", "rule" to "log-school"),
        line("Classification rule matched").mdc
    )
    val classificationAction = line("Action done: Reply needed").mdc
    assertEquals("classification", classificationAction["phase"])
    assertEquals("log-school", classificationAction["rule"])
    assertEquals("set_reply_needed", classificationAction["action"])
    val readAction = line("Action done: Marked read").mdc
    assertEquals("action", readAction["phase"])
    assertEquals("log-read", readAction["rule"])
    assertEquals("mark_read", readAction["action"])
    // Each key goes once its work ends
    val finished = line("Email processed").mdc
    assertEquals(setOf("run", "email", "message_id"), finished.keys)
    assertEquals(mapOf("run" to runId), line("Processing run finished").mdc)
    assertTrue(MDC.getMap().isEmpty(), "nothing is left in the MDC: ${MDC.getMap()}")
  }

  @Test
  fun `manual actions carry the email, action and source`() {
    inbox(message("<logging-manual@mail.example>", "Manual logging"))
    processing.runNow(RunTrigger.MANUAL)
    val emailId = email("logging-manual@mail.example")["id"].toString()

    htmx().formParam("required", "true").post("/emails/$emailId/response-required")

    assertEquals(
        mapOf("email" to emailId, "action" to "set_response_required", "source" to "ui"),
        line("Manual action done").mdc
    )
  }

  @Test
  fun `logs go to stdout with the MDC, and not to a file`() {
    val config = ConfigProvider.getConfig()

    assertTrue(config.getValue("quarkus.log.console.enable", Boolean::class.java))
    assertFalse(config.getValue("quarkus.log.console.stderr", Boolean::class.java))
    assertTrue(config.getValue("quarkus.log.console.format", String::class.java).contains("%X"))
    assertFalse(config.getValue("quarkus.log.file.enable", Boolean::class.java))
  }
}
