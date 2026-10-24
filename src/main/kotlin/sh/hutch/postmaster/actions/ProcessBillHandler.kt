package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.EmailEventEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.CalendarEvent
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.services.CalendarClient
import sh.hutch.postmaster.services.HtmlParser
import sh.hutch.postmaster.services.HttpFetcher
import sh.hutch.postmaster.services.LlmClient
import sh.hutch.postmaster.services.LlmException
import sh.hutch.postmaster.services.MailboxClient
import sh.hutch.postmaster.services.PromptRenderer
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.util.Times
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads a bill: who to pay, how much, by when, and the links to pay and to get the bill. Then it
 * adds a reminder the day before the due date to the action's calendar and downloads the bill
 * (REQUIREMENTS.md "process bill", CLARIFICATIONS.md Q16, D14). Only for emails of the Bill type.
 */
@ApplicationScoped
class ProcessBillHandler(
    private val settings: SettingsService,
    private val llm: LlmClient,
    private val prompts: PromptRenderer,
    private val calendar: CalendarClient,
    private val mailbox: MailboxClient,
    private val fetcher: HttpFetcher,
    private val store: AttachmentStore
) : ActionHandler {
  override val actionType = ActionTypes.PROCESS_BILL
  override val stage = ActionStage.GATHER
  override val order = 50

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val email = context.email
    if (email.type?.isBill != true) {
      return ActionOutcome.skipped("Not a bill")
    }
    val html = email.htmlMessage
    val text = email.message ?: html?.let { HtmlParser.text(it) }.orEmpty()
    var amount = BillParser.amount(text)
    var dueDate = BillParser.dueDate(text)
    var payLink = html?.let { HtmlParser.findLinks(it, settings.getList(SettingKeys.BILLS_PAY_LINK_TEXT)).firstOrNull()?.href }
    val pdf = email.attachments.firstOrNull { it.partPath != null && isPdf(it.filename, it.contentType) }
    var billUrl = if (pdf == null) html?.let { HtmlParser.findLinks(it, settings.getList(SettingKeys.BILLS_BILL_LINK_TEXT)).firstOrNull()?.href } else null
    var biller = email.senderName?.takeIf { it.isNotBlank() } ?: email.sender?.substringAfter('@')

    if (amount == null || dueDate == null || payLink == null) {
      try {
        val answer = llm.chatJson(prompts.render("bill_details", EventFinder.promptContext(email)))
        if (biller == null) biller = answer.path("organisation").asText("").ifBlank { null }
        if (amount == null) amount = answer.path("amount_due").takeIf { it.isNumber || it.isTextual }?.asText()?.replace(Regex("[$,\\s]"), "")?.toBigDecimalOrNull()
        if (dueDate == null) dueDate = answer.path("due_date").asText("").let { parseDate(it) }
        if (payLink == null) payLink = answer.path("payment_link").asText("").ifBlank { null }
        if (billUrl == null && pdf == null) billUrl = answer.path("bill_url").asText("").ifBlank { null }
      } catch (e: LlmException) {
        context.addError(ErrorPhase.LLM, "bill_details: ${e.message}")
      }
    }
    if (amount == null && dueDate == null && payLink == null && billUrl == null && pdf == null) {
      return ActionOutcome.failed("No bill details were found")
    }

    email.billSender = biller?.take(255)
    email.billAmount = amount?.setScale(2, java.math.RoundingMode.HALF_UP)
    email.billDueDate = dueDate
    email.billPayLink = payLink?.take(2000)
    email.billDownloadUrl = billUrl?.take(2000)

    val notes = mutableListOf<String>()
    if (dueDate != null) {
      notes += reminder(context, action, biller, amount, dueDate, payLink)
    } else {
      context.addError(ErrorPhase.ACTION, "process_bill: no due date was found, so no reminder was added")
    }
    notes += download(context, pdf, billUrl)
    val found = listOfNotNull(
        biller?.let { "biller $it" }, amount?.let { "amount $${money(it)}" }, dueDate?.let { "due $it" },
        payLink?.let { "pay link" }
    ).joinToString(", ")
    return ActionOutcome.done((listOf("Found $found") + notes.filter { it.isNotEmpty() }).joinToString("; "))
  }

  private fun reminder(
      context: ProcessingContext,
      action: RuleActionEntity,
      biller: String?,
      amount: BigDecimal?,
      dueDate: LocalDate,
      payLink: String?
  ): String {
    val target = action.calendar?.takeIf { !it.deleted } ?: run {
      context.addError(ErrorPhase.ACTION, "process_bill: the action has no calendar, so no reminder was added")
      return ""
    }
    val title = "Pay ${biller ?: "bill"}" + (amount?.let { " $${money(it)}" } ?: "")
    val notes = listOfNotNull(
        biller?.let { "Biller: $it" }, amount?.let { "Amount: $${money(it)}" }, "Due: $dueDate", payLink?.let { "Pay: $it" }
    ).joinToString("\n")
    val day = dueDate.minusDays(1)
    // Reprocessing keeps added events, so the reminder may already be there
    if (context.email.events.any { it.isAdded && it.allDay && it.title == title && it.startsAt == day.atStartOfDay() }) {
      return "reminder on $day already added"
    }
    val uid = calendar.addEvent(
        target.name,
        CalendarEvent("pm-${context.email.id}-bill-$day", title, day.atStartOfDay(), null, true, null, notes, emptyList())
    )
    context.email.events.add(EmailEventEntity().also {
      it.email = context.email
      it.calendar = target
      it.title = title
      it.startsAt = day.atStartOfDay()
      it.allDay = true
      it.notes = notes
      it.isAdded = true
      it.khalUid = uid
    })
    return "reminder on $day in ${target.name}"
  }

  private fun download(context: ProcessingContext, pdf: sh.hutch.postmaster.entities.EmailAttachmentEntity?, billUrl: String?): String {
    val email = context.email
    if (pdf != null) {
      if (pdf.storedPath == null) {
        val (folder, uid) = email.location(mailbox)
        val relative = store.newFile(email, pdf.filename)
        mailbox.saveAttachment(folder, uid, pdf.partPath!!, store.resolve(relative))
        pdf.storedPath = relative
        pdf.downloadedAt = Times.now()
      }
      return "bill ${pdf.filename} saved"
    }
    if (billUrl == null) {
      return ""
    }
    val filename = runCatching { URI(billUrl).path.substringAfterLast('/') }.getOrNull()?.takeIf { it.isNotBlank() } ?: "bill.pdf"
    val relative = store.newFile(email, filename)
    val file = fetcher.download(billUrl, store.resolve(relative))
    store.addDownloaded(email, filename, relative, file.contentType, file.size)
    return "bill downloaded from $billUrl"
  }

  private fun isPdf(filename: String, contentType: String?) =
    contentType.equals("application/pdf", ignoreCase = true) || filename.endsWith(".pdf", ignoreCase = true)

  private fun parseDate(text: String): LocalDate? =
    try {
      text.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it.take(10)) }
    } catch (e: DateTimeParseException) {
      null
    }

  private fun money(amount: BigDecimal) = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}
