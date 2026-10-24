package sh.hutch.postmaster.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.icegreen.greenmail.util.GreenMail
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import sh.hutch.postmaster.models.ActionInput
import sh.hutch.postmaster.models.CalendarInput
import sh.hutch.postmaster.models.ConditionInput
import sh.hutch.postmaster.models.RuleInput
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.services.CalendarService
import sh.hutch.postmaster.services.LlmClient
import sh.hutch.postmaster.services.MailboxClient
import sh.hutch.postmaster.services.ProcessingService
import sh.hutch.postmaster.services.RuleService
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.GreenMailTestResource
import sh.hutch.postmaster.testsupport.InjectGreenMail
import sh.hutch.postmaster.testsupport.InjectWireMock
import sh.hutch.postmaster.testsupport.PipelineProfile
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.WireMockTestResource
import sh.hutch.postmaster.testsupport.rows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Base for the processing pipeline tests: GreenMail stands in for Dovecot and
 * SMTP, WireMock for the LLM and web pages, and the fake khal for calendars.
 * Each test starts with an empty mailbox, no rules, no calendars and LLM
 * stubs that answer with nothing in particular.
 */
@TestProfile(PipelineProfile::class)
@WithTestResource(GreenMailTestResource::class)
@WithTestResource(WireMockTestResource::class)
abstract class PipelineTest {
  @field:InjectGreenMail
  lateinit var greenMail: GreenMail

  @field:InjectWireMock
  lateinit var wireMock: WireMockServer

  @Inject
  lateinit var processing: ProcessingService

  @Inject
  lateinit var settings: SettingsService

  @Inject
  lateinit var rules: RuleService

  @Inject
  lateinit var calendars: CalendarService

  @Inject
  lateinit var types: TypeRepository

  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var llmClient: LlmClient

  @Inject
  lateinit var mailboxClient: MailboxClient

  lateinit var attachments: Path

  private val mapper = ObjectMapper()

  @BeforeEach
  fun setUpPipeline() {
    greenMail.purgeEmailFromAllMailboxes()
    TestMessages.createFolders(greenMail, "Trash", "Folders/Bills", "Folders/School")
    wireMock.resetAll()
    FakeTools.reset()
    // Failing calls are retried without waiting
    llmClient.retryDelays = listOf(Duration.ZERO)
    mailboxClient.reconnectDelay = Duration.ZERO
    attachments = Files.createTempDirectory("postmaster-attachments")
    settings.update("imap.host", "127.0.0.1")
    settings.update("imap.port", greenMail.imap.port.toString())
    settings.update("imap.username", GreenMailTestResource.USER)
    settings.update("imap.password", GreenMailTestResource.PASSWORD)
    settings.update("smtp.host", "127.0.0.1")
    settings.update("smtp.port", greenMail.smtp.port.toString())
    settings.update("smtp.from_address", "postmaster@fulcrum.test")
    settings.update("llm.url", wireMock.baseUrl() + "/v1")
    settings.update("llm.timeout", "10s")
    settings.update("attachments.path", attachments.toString())
    settings.update("attachments.extensions", "pdf")
    settings.update("processing.lookback", "3650d")
    // By default the LLM classifies nothing and summarises everything the same way
    llm(CLASSIFY, "NO CLASSIFICATION")
    llm(SUMMARISE, """{"summary": "A summary"}""")
    llm(IDENTIFY_EVENTS, """{"events": []}""")
    llm(SELECT_CALENDAR, "{}")
    llm(SUMMARISE_PAGE, "A page summary")
    llm(BILL_DETAILS, "{}")
  }

  @AfterEach
  fun removeRulesAndCalendars() {
    for (type in RuleType.entries) {
      rules.list(type).forEach { rules.delete(type, it.id!!) }
    }
    calendars.list().forEach { calendars.delete(it.id!!) }
  }

  /** Answers prompts containing [marker] with [content] (later stubs win) */
  fun llm(marker: String, content: String, status: Int = 200, delayMs: Int = 0) {
    val body = mapper.writeValueAsString(
        mapOf("choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content))))
    )
    wireMock.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .withRequestBody(containing(marker))
            .willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)
                    .withFixedDelay(delayMs)
            )
    )
  }

  /** How many LLM requests contained [marker] */
  fun llmCalls(marker: String): Int =
      wireMock.allServeEvents.count { it.request.url == "/v1/chat/completions" && it.request.bodyAsString.contains(marker) }

  fun inbox(vararg messages: MimeMessage) = TestMessages.append(greenMail, "INBOX", *messages)

  fun condition(attribute: String, matchType: String, test: String? = null, index: Int = 0) =
      ConditionInput(index, null, attribute, matchType, test)

  fun action(
      type: String,
      index: Int = 0,
      destination: String? = null,
      typeName: String? = null,
      calendar: String? = null,
      notifications: String? = null
  ) = ActionInput(
      index, null, type, destination,
      typeName?.let { types.findActiveByName(it)!!.id.toString() },
      calendar?.let { name -> calendars.list().single { it.name == name }.id.toString() },
      notifications, true
  )

  fun rule(
      type: RuleType,
      name: String,
      conditions: List<ConditionInput>,
      actions: List<ActionInput>,
      priority: Int = 0
  ) = rules.create(type, RuleInput(name, "Test rule $name", priority.toString(), conditions, actions))

  fun calendar(name: String, periods: String? = null) =
      calendars.create(CalendarInput(name, "Calendar $name", "#50fa7b", true, periods))

  /** The email record with this Message-ID (stored without angle brackets) */
  fun email(messageId: String): Map<String, Any?> =
      dataSource.rows(
          "SELECT e.*, t.name AS type_name FROM Emails e LEFT JOIN Types t ON t.id = e.type_id WHERE e.message_id = ?",
          messageId
      ).single()

  fun actionResults(messageId: String): List<Map<String, Any?>> =
      dataSource.rows(
          "SELECT r.action_type, r.status, r.detail, ru.name AS rule_name FROM Email_action_results r " +
              "JOIN Emails e ON e.id = r.email_id LEFT JOIN Rules ru ON ru.id = r.rule_id " +
              "WHERE e.message_id = ? ORDER BY r.id",
          messageId
      )

  fun errors(messageId: String): List<Map<String, Any?>> =
      dataSource.rows(
          "SELECT x.phase, x.message FROM Email_errors x JOIN Emails e ON e.id = x.email_id WHERE e.message_id = ? ORDER BY x.id",
          messageId
      )

  fun ruleMatches(messageId: String): List<String> =
      dataSource.rows(
          "SELECT r.name FROM Email_rule_matches m JOIN Rules r ON r.id = m.rule_id JOIN Emails e ON e.id = m.email_id " +
              "WHERE e.message_id = ? ORDER BY m.position, m.id",
          messageId
      ).map { it["name"] as String }

  fun events(messageId: String): List<Map<String, Any?>> =
      dataSource.rows(
          "SELECT ev.*, c.name AS calendar_name FROM Email_events ev JOIN Emails e ON e.id = ev.email_id " +
              "LEFT JOIN Calendars c ON c.id = ev.calendar_id WHERE e.message_id = ? ORDER BY ev.id",
          messageId
      )

  companion object {
    /** Text found only in each seeded prompt, to tell the LLM requests apart */
    const val CLASSIFY = "Select one of the following email types"
    const val SUMMARISE = "Attempt to identify if a response is required"
    const val IDENTIFY_EVENTS = "such as appointments, meetings"
    const val SELECT_CALENDAR = "select one of these calendars"
    const val SUMMARISE_PAGE = "Summarise the contents of this webpage"
    const val BILL_DETAILS = "The organisation to pay"
  }
}
