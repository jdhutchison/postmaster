package sh.hutch.postmaster.config

import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import sh.hutch.postmaster.entities.CalendarEntity
import sh.hutch.postmaster.entities.ConditionEntity
import sh.hutch.postmaster.entities.EmailActionItemEntity
import sh.hutch.postmaster.entities.EmailActionResultEntity
import sh.hutch.postmaster.entities.EmailAttachmentEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailErrorEntity
import sh.hutch.postmaster.entities.EmailEventEntity
import sh.hutch.postmaster.entities.EmailRuleMatchEntity
import sh.hutch.postmaster.entities.ProcessingRunEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.entities.TypeEntity
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.ConditionAttribute
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.MatchType
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.ProcessingRunRepository
import sh.hutch.postmaster.repositories.RuleRepository
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.services.TodoList
import sh.hutch.postmaster.services.TodoListService
import sh.hutch.postmaster.util.Times
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Fake data for dev mode (`./gradlew quarkusDev`, which uses an in-memory database), so every page
 * has something to show: calendars, rules, about 60 emails in every state, and processing runs.
 * Only built into the dev profile. The khal config and attachment files it refers to are written
 * under `build/dev/`.
 */
@IfBuildProfile("dev")
@ApplicationScoped
class DevData(
    private val config: PostmasterConfig,
    private val settings: SettingsService,
    private val types: TypeRepository,
    private val calendars: CalendarRepository,
    private val rules: RuleRepository,
    private val emails: EmailRepository,
    private val runs: ProcessingRunRepository,
    private val todos: TodoListService
) {
  private val random = Random(42)
  private val now = Times.now()

  fun onStart(@Observes event: StartupEvent) {
    val khalConfig = Path.of(config.khalConfig()).toAbsolutePath()
    val root = khalConfig.parent
    val attachments = root.resolve("attachments")
    writeKhalConfig(khalConfig, root.resolve("calendars"))
    settings.update(SettingKeys.ATTACHMENTS_PATH, attachments.toString())
    settings.update(SettingKeys.ATTACHMENTS_EXTENSIONS, "pdf, png, txt")
    // Dev mode never contacts the real LLM (or anything else on kodiakbear or tigerbear)
    settings.update(SettingKeys.LLM_URL, "http://127.0.0.1:9931/v1")
    val todo = root.resolve("todo")
    settings.update(SettingKeys.TODO_PATH, todo.toString())
    settings.update(SettingKeys.TODO_POSTMASTER_URL, "http://localhost:8080/mail")
    QuarkusTransaction.requiringNew().run {
      if (emails.count() == 0L) {
        writeTodoIndex(todo)
        seed(attachments)
      }
    }
    LOG.infof("Dev data: %d emails, khal config %s, attachments in %s, to-do list in %s", emails.count(), khalConfig, attachments, todo)
  }

  // ---------------------------------------------------------------------------------------------

  private fun seed(attachments: Path) {
    val family = calendar("Family", "Family events, birthdays and outings", "#bd93f9", "1d")
    val school = calendar("Kids school", "School events, excursions and parent meetings", "#50fa7b", "1d, 2h")
    val sport = calendar("Sport", "Dancing, football training and matches", "#ffb86c", "2h")
    val bills = calendar("Bills", "Bill payment reminders", "#ff5555", "1d")
    calendar("Old calendar", "No longer used", "#6272a4", null, enabled = false)

    val bill = types.findBill()!!
    val primary = type("Primary school")
    val high = type("High school")
    val statement = type("Statement notification")
    val payslip = type("Payslip")
    val showfit = type("Showfit")
    val ebay = type("Ebay")

    val billRule = rule(RuleType.CLASSIFICATION, "Power and water bills", "Bills from the utilities", 20,
        listOf(cond(ConditionAttribute.SENDER, MatchType.REGEX, "(?i)@(powerco|citywater)\\.example$")),
        listOf(act(ActionTypes.SET_TYPE, type = bill)))
    val primaryRule = rule(RuleType.CLASSIFICATION, "Brunswick South", "Messages from the primary school", 10,
        listOf(cond(ConditionAttribute.SENDER, MatchType.CONTAINS, "brunswicksouthps")),
        listOf(act(ActionTypes.SET_TYPE, type = primary), act(ActionTypes.CHECK_FOR_EVENTS)))
    val highRule = rule(RuleType.CLASSIFICATION, "Princes Hill", "Messages from the high school", 10,
        listOf(cond(ConditionAttribute.SENDER, MatchType.CONTAINS, "princeshill")),
        listOf(act(ActionTypes.SET_TYPE, type = high), act(ActionTypes.SET_REPLY_NEEDED)))
    val payslipRule = rule(RuleType.CLASSIFICATION, "Payslips", "Monthly payslips", 5,
        listOf(cond(ConditionAttribute.SUBJECT, MatchType.STARTS_WITH, "Your payslip"), cond(ConditionAttribute.ATTACHMENTS, MatchType.HAS)),
        listOf(act(ActionTypes.SET_TYPE, type = payslip), act(ActionTypes.DOWNLOAD_ATTACHMENTS)))
    val showfitRule = rule(RuleType.CLASSIFICATION, "Showfit", "Jeremy's dancing", 5,
        listOf(cond(ConditionAttribute.SENDER, MatchType.CONTAINS, "showfit")),
        listOf(act(ActionTypes.SET_TYPE, type = showfit)))
    val newsletterRule = rule(RuleType.CLASSIFICATION, "Newsletters", "Skip newsletters entirely", 0,
        listOf(cond(ConditionAttribute.SUBJECT, MatchType.CONTAINS, "newsletter")),
        listOf(act(ActionTypes.SKIP_PROCESSING)), enabled = false)
    val payBills = rule(RuleType.ACTION, "Pay bills", "Reminders and downloads for bills, then file them", 20,
        listOf(cond(ConditionAttribute.TYPE, MatchType.IS, bill.name)),
        listOf(act(ActionTypes.PROCESS_BILL, calendar = bills), act(ActionTypes.MOVE, destination = "Folders/Bills")))
    val schoolEvents = rule(RuleType.ACTION, "School events", "Add school events to the school calendar", 10,
        listOf(cond(ConditionAttribute.TYPE, MatchType.REGEX, "school$"), cond(ConditionAttribute.EVENTS, MatchType.HAS)),
        listOf(act(ActionTypes.CREATE_EVENTS, calendar = school, notifications = "1d"), act(ActionTypes.MARK_READ)))
    val dancing = rule(RuleType.ACTION, "Dancing events", "Rehearsals and concerts", 10,
        listOf(cond(ConditionAttribute.TYPE, MatchType.IS, showfit.name), cond(ConditionAttribute.EVENTS, MatchType.HAS)),
        listOf(act(ActionTypes.CREATE_EVENTS, calendar = sport)))
    val forwardPayslips = rule(RuleType.ACTION, "Forward payslips", "Send payslips to the accountant", 0,
        listOf(cond(ConditionAttribute.TYPE, MatchType.IS, payslip.name)),
        listOf(act(ActionTypes.FORWARD_EMAIL, destination = "accounts@example.test")))
    val todoRule = rule(RuleType.ACTION, "To-do list", "Put what school and eBay email asks for on the to-do list", 5,
        listOf(cond(ConditionAttribute.TYPE, MatchType.REGEX, "school$|^Ebay$")),
        listOf(act(ActionTypes.UPDATE_TODO_LIST)))
    rule(RuleType.ACTION, "Archive statements", "File statement notifications", 0,
        listOf(cond(ConditionAttribute.TYPE, MatchType.IS, statement.name)),
        listOf(act(ActionTypes.MARK_READ), act(ActionTypes.MOVE, destination = "Archive")))

    val pdf = devPdf("Electricity bill", "Amount due: \$182.40", "Due by 14 Nov 2026")
    val png = devPng()

    // Bills: processed with every bill detail, one waiting for its download
    for ((i, name) in listOf("Power Co", "City Water", "Power Co").withIndex()) {
      val domain = if (name == "Power Co") "powerco.example" else "citywater.example"
      val due = LocalDate.now(ZoneOffset.UTC).plusDays(10L + i * 7)
      val amount = BigDecimal("${120 + i * 37}.${10 + i * 25}")
      email(
          "Your $name bill is ready", name, "accounts@$domain", hoursAgo = 20 + i * 60, type = bill,
          text = "Hi,\n\nYour bill for the last quarter is ready.\n\nAmount due: \$$amount\nPay by ${DAY.format(due)}\n\nPay now: https://$domain/pay\n",
          summary = "$name quarterly bill of \$$amount, due ${DAY.format(due)}.", folder = "Folders/Bills"
      ) { e ->
        e.billSender = name
        e.billAmount = amount
        e.billDueDate = due
        e.billPayLink = "https://$domain/pay/${1000 + i}"
        e.billDownloadUrl = if (i == 1) "https://$domain/bills/${2000 + i}.pdf" else null
        e.movedTo = "Folders/Bills"
        if (i != 1) {
          attach(e, attachments, "bill-$i.pdf", "application/pdf", pdf)
        }
        event(e, "Pay $name \$$amount", due.minusDays(1), null, bills, added = true)
        matched(e, billRule, 0, ActionTypes.SET_TYPE to "Type set to Bill")
        matched(e, payBills, 1, ActionTypes.PROCESS_BILL to "Found biller $name, amount \$$amount, due $due, pay link; reminder on ${due.minusDays(1)} in Bills",
            ActionTypes.MOVE to "Moved to Folders/Bills")
      }
    }

    // School: events added and suggested, replies and actions needed
    val schoolNotes = listOf(
        Triple("Excursion to the zoo: permission form", "Please sign and return the permission form by Friday.", "Zoo excursion"),
        Triple("Parent-teacher interviews", "Book a time for the interviews next Thursday.", "Parent-teacher interviews"),
        Triple("School fete this Saturday", "Volunteers are needed for the cake stall.", "School fete"),
        Triple("Book week parade", "Children can dress up as a book character.", "Book week parade"),
        Triple("Year 8 camp payment", "The camp payment of \$420 is due by the end of the month.", "Year 8 camp")
    )
    for ((i, note) in schoolNotes.withIndex()) {
      val (subject, text, title) = note
      val isHigh = i == 4
      val start = LocalDate.now(ZoneOffset.UTC).plusDays(3L + i * 4).atTime(LocalTime.of(9 + i, 0))
      email(
          subject, if (isHigh) "Princes Hill SC" else "Brunswick South PS",
          if (isHigh) "office@princeshill.example" else "office@brunswicksouthps.example",
          hoursAgo = 5 + i * 31, type = if (isHigh) high else primary,
          text = "Dear families,\n\n$text\n\nKind regards,\nThe office",
          summary = text, read = i % 2 == 0, link = if (i == 0) "https://brunswicksouthps.example/excursion" else null
      ) { e ->
        e.responseRequired = isHigh || i == 1
        when (i) {
          0 -> {
            todo(e, e.addActionItem("Sign and return the zoo excursion permission form", friday(e),
                "The permission form is attached. The excursion costs \$35; bring a hat, water bottle and lunch."))
            e.addActionItem("Pay \$35 for the zoo excursion", friday(e), "Pay through the school app.")
                ?.let { e.completeActionItem(it) }
          }
          4 -> todo(e, e.addActionItem("Pay \$420 for Year 8 camp", LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(1).minusDays(1)))
        }
        val added = i != 3
        event(e, title, null, start, school, added = added, location = if (i == 0) "Melbourne Zoo" else "School hall")
        if (i == 0) {
          e.linkSummary = "The excursion page lists the cost (\$35), what to bring, and the bus times."
          e.linkContents = "Zoo excursion\n\nCost: \$35\nBring: hat, water bottle, lunch\nBus leaves 8:45am, returns 2:30pm"
          attach(e, attachments, "permission-form.pdf", "application/pdf", devPdf("Permission form", "Zoo excursion", "Return by Friday"))
          attach(e, attachments, "zoo-map.png", "image/png", png)
        }
        matched(e, if (isHigh) highRule else primaryRule, 0,
            *(if (isHigh) arrayOf(ActionTypes.SET_TYPE to "Type set to High school", ActionTypes.SET_REPLY_NEEDED to "Reply needed")
            else arrayOf(ActionTypes.SET_TYPE to "Type set to Primary school", ActionTypes.CHECK_FOR_EVENTS to "1 event(s) found, 1 new")))
        if (added) {
          matched(e, schoolEvents, 1, ActionTypes.CREATE_EVENTS to "Added $title (Kids school)", ActionTypes.MARK_READ to "Marked read")
        }
        e.actionItems.firstOrNull()?.todoName?.let { matched(e, todoRule, 2, ActionTypes.UPDATE_TODO_LIST to "Added to the to-do list: $it") }
      }
    }

    // Dancing, payslips and statements
    for (i in 0 until 4) {
      val start = LocalDate.now(ZoneOffset.UTC).plusDays(2L + i * 7).atTime(LocalTime.of(16, 30))
      email("Showfit rehearsal schedule, week ${i + 1}", "Showfit", "studio@showfit.example", hoursAgo = 30 + i * 70, type = showfit,
          text = "Rehearsal this week is on ${DAY.format(start.toLocalDate())} at 4:30pm.", summary = "Rehearsal on ${DAY.format(start.toLocalDate())} at 4:30pm.") { e ->
        event(e, "Showfit rehearsal", null, start, sport, added = true, location = "Showfit studio")
        matched(e, showfitRule, 0, ActionTypes.SET_TYPE to "Type set to Showfit")
        matched(e, dancing, 1, ActionTypes.CREATE_EVENTS to "Added Showfit rehearsal (Sport)")
      }
    }
    email("Your payslip for October", "Payroll", "payroll@employer.example", hoursAgo = 50, type = payslip,
        text = "Your payslip is attached.", summary = "October payslip.") { e ->
      attach(e, attachments, "payslip-2026-10.pdf", "application/pdf", devPdf("Payslip", "October 2026", "Net pay: \$4,210.00"))
      matched(e, payslipRule, 0, ActionTypes.SET_TYPE to "Type set to Payslip", ActionTypes.DOWNLOAD_ATTACHMENTS to "Downloaded payslip-2026-10.pdf")
      matched(e, forwardPayslips, 1)
      result(e, forwardPayslips, ActionTypes.FORWARD_EMAIL, ActionResultStatus.FAILED, "The SMTP server rejected the username or password")
      error(e, ErrorPhase.ACTION, "forward_email: The SMTP server rejected the username or password")
    }
    for (i in 0 until 3) {
      email("Your ${listOf("credit card", "savings", "home loan")[i]} statement is available", "Example Bank", "statements@bank.example",
          hoursAgo = 100 + i * 45, type = statement, read = true, folder = "Archive",
          text = "Your statement is now available in online banking.", summary = "A new statement is available in online banking.") { e ->
        e.movedTo = "Archive"
      }
    }
    email("Item sold: vintage turntable", "eBay", "ebay@ebay.example", hoursAgo = 8, type = ebay,
        text = "Congratulations, your item sold for \$240. Please post it within 3 business days.",
        summary = "The vintage turntable sold for \$240 and needs posting within 3 business days.") { e ->
      todo(e, e.addActionItem("Post the turntable", e.receivedAt!!.atZone(ZoneOffset.UTC).toLocalDate().plusDays(3),
          "It sold for \$240. Post it within 3 business days of the sale."))
      matched(e, todoRule, 0, ActionTypes.UPDATE_TODO_LIST to "Added to the to-do list: Post the turntable")
    }

    // Unclassified, skipped, failed and pending email
    val senders = listOf(
        "Friend" to "sam@friends.example", "Newsletter" to "news@newsletter.example", "Hardware store" to "deals@hardware.example",
        "Council" to "rates@council.example", "Dentist" to "reception@dentist.example", "Library" to "loans@library.example"
    )
    val subjects = listOf(
        "Lunch next week?", "This week's newsletter", "Spring sale ends Sunday", "Bin collection changes",
        "Appointment reminder", "Your loans are due soon", "Photos from the weekend", "Weekly newsletter: what's on"
    )
    for (i in 0 until 40) {
      val (name, address) = senders[i % senders.size]
      val subject = subjects[i % subjects.size] + if (i >= subjects.size) " (${i / subjects.size + 1})" else ""
      email(subject, name, address, hoursAgo = 2 + i * 8 + random.nextInt(6), read = random.nextBoolean(),
          text = "Hello,\n\n${subject}.\n\nThanks", summary = if (i % 5 == 4) null else "A message about: ${subject.lowercase()}.") { e ->
        when {
          i == 3 -> {
            e.status = EmailStatus.FAILED
            error(e, ErrorPhase.LLM, "summarise: LLM request timed out after 2m")
            error(e, ErrorPhase.ACTION, "Processing failed: Dovecot (mail-dovecot) could not be reached")
          }
          i % 5 == 4 -> error(e, ErrorPhase.LLM, "summarise: LLM could not be reached at http://kodiakbear:9931/v1")
          i == 7 -> e.status = EmailStatus.PENDING
          i % 9 == 1 -> e.status = EmailStatus.SKIPPED
          i % 6 == 0 -> e.responseRequired = true
        }
        if (i == 12) {
          attach(e, attachments, "notes.txt", "text/plain", "Notes from the meeting:\n- bring photos\n- book table\n".toByteArray())
        }
        if (i == 15) {
          result(e, null, ActionTypes.MOVE, ActionResultStatus.DONE, "Moved to Archive", source = ActionSource.UI)
          e.folder = "Archive"
          e.movedTo = "Archive"
        }
      }
    }

    // Processing runs, oldest first
    for (i in 8 downTo 0) {
      val run = ProcessingRunEntity()
      run.triggerSource = if (i == 2) RunTrigger.MANUAL else RunTrigger.SCHEDULE
      run.startedAt = now.minus(Duration.ofHours(i.toLong()).plusMinutes(5))
      run.finishedAt = run.startedAt!!.plusSeconds(20L + random.nextInt(90))
      if (i == 4) {
        run.status = RunStatus.FAILED
        run.error = "Dovecot (mail-dovecot) could not be reached: Connection refused"
      } else {
        run.status = RunStatus.SUCCEEDED
        run.emailsFound = random.nextInt(0, 7)
        run.emailsProcessed = run.emailsFound - (if (i == 6 && run.emailsFound > 0) 1 else 0)
        run.emailsFailed = run.emailsFound - run.emailsProcessed
      }
      runs.persist(run)
    }
  }

  // ---------------------------------------------------------------------------------------------

  private fun type(name: String): TypeEntity = types.findActiveByName(name)!!

  private fun calendar(name: String, description: String, colour: String, periods: String?, enabled: Boolean = true): CalendarEntity {
    val calendar = CalendarEntity()
    calendar.name = name
    calendar.description = description
    calendar.colour = colour
    calendar.notificationPeriods = periods
    calendar.enabled = enabled
    calendars.persist(calendar)
    return calendar
  }

  private fun cond(attribute: ConditionAttribute, matchType: MatchType, test: String? = null) =
    ConditionEntity().also {
      it.attribute = attribute
      it.matchType = matchType
      it.test = test
    }

  private fun act(
      actionType: String,
      type: TypeEntity? = null,
      calendar: CalendarEntity? = null,
      destination: String? = null,
      notifications: String? = null
  ) = RuleActionEntity().also {
    it.actionType = actionType
    it.type = type
    it.calendar = calendar
    it.destination = destination
    it.notifications = notifications
  }

  private fun rule(
      ruleType: RuleType,
      name: String,
      description: String,
      priority: Int,
      conditions: List<ConditionEntity>,
      actions: List<RuleActionEntity>,
      enabled: Boolean = true
  ): RuleEntity {
    val rule = RuleEntity()
    rule.ruleType = ruleType
    rule.name = name
    rule.description = description
    rule.priority = priority
    rule.enabled = enabled
    conditions.forEachIndexed { i, c ->
      c.rule = rule
      c.position = i
      rule.conditions.add(c)
    }
    actions.forEachIndexed { i, a ->
      a.rule = rule
      a.position = i
      rule.actions.add(a)
    }
    rules.persist(rule)
    return rule
  }

  private fun email(
      subject: String,
      senderName: String,
      sender: String,
      hoursAgo: Int,
      type: TypeEntity? = null,
      text: String,
      summary: String?,
      read: Boolean = false,
      folder: String = "INBOX",
      link: String? = null,
      details: (EmailEntity) -> Unit = {}
  ) {
    val email = EmailEntity()
    email.messageId = "dev-${emails.count() + 1}-${subject.hashCode()}@dev.example"
    email.subject = subject
    email.sender = sender
    email.senderName = senderName
    email.message = text
    email.htmlMessage = "<p>" + text.replace("\n\n", "</p><p>").replace("\n", "<br>") + "</p>"
    email.summary = summary
    email.link = link
    email.isRead = read
    email.folder = folder
    email.imapUid = 1000 + emails.count()
    email.receivedAt = now.minus(Duration.ofHours(hoursAgo.toLong())).minusSeconds(random.nextLong(0, 3000))
    email.processedAt = email.receivedAt!!.plusSeconds(random.nextLong(60, 3600))
    email.status = EmailStatus.PROCESSED
    email.assignType(type)
    emails.persist(email)
    details(email)
  }

  /** A to-do list with just its heading and the line that items go above. */
  private fun writeTodoIndex(todo: Path) {
    if (Files.isDirectory(todo)) {
      Files.list(todo).use { files -> files.filter { it.fileName.toString().endsWith(".md") }.forEach(Files::delete) }
    }
    Files.createDirectories(todo)
    Files.writeString(todo.resolve(TodoList.INDEX), "# To-do\n\n<!-- todo-items:start -->\n${TodoList.END_MARKER}\n")
  }

  /** Writes [item] to the to-do list, as update_todo_list would. */
  private fun todo(email: EmailEntity, item: EmailActionItemEntity?) {
    if (item != null) {
      emails.flush()
      todos.add(email, item)
    }
  }

  /** The Friday after the email was received. */
  private fun friday(email: EmailEntity): LocalDate =
    email.receivedAt!!.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.FRIDAY))

  private fun matched(email: EmailEntity, rule: RuleEntity, position: Int, vararg results: Pair<String, String>) {
    email.ruleMatches.add(EmailRuleMatchEntity().also {
      it.email = email
      it.rule = rule
      it.ruleType = rule.ruleType
      it.position = position
      it.matchedAt = email.processedAt
    })
    for ((actionType, detail) in results) {
      result(email, rule, actionType, ActionResultStatus.DONE, detail)
    }
  }

  private fun result(
      email: EmailEntity,
      rule: RuleEntity?,
      actionType: String,
      status: ActionResultStatus,
      detail: String,
      source: ActionSource = ActionSource.RULE
  ) {
    email.actionResults.add(EmailActionResultEntity().also {
      it.email = email
      it.rule = rule
      it.ruleAction = rule?.actions?.firstOrNull { a -> a.actionType == actionType }
      it.actionType = actionType
      it.source = source
      it.status = status
      it.detail = detail
      it.executedAt = email.processedAt
    })
  }

  private fun error(email: EmailEntity, phase: ErrorPhase, message: String) {
    email.errors.add(EmailErrorEntity().also {
      it.email = email
      it.phase = phase
      it.message = message
      it.createdAt = email.processedAt
    })
  }

  private fun event(
      email: EmailEntity,
      title: String,
      day: LocalDate?,
      start: java.time.LocalDateTime?,
      calendar: CalendarEntity,
      added: Boolean,
      location: String? = null
  ) {
    email.events.add(EmailEventEntity().also {
      it.email = email
      it.title = title
      it.allDay = day != null
      it.startsAt = start ?: day!!.atStartOfDay()
      it.endsAt = start?.plusHours(1)
      it.location = location
      it.calendar = calendar
      it.isAdded = added
      it.khalUid = if (added) "pm-dev-${email.messageId.hashCode()}-${title.hashCode()}" else null
    })
  }

  private fun attach(email: EmailEntity, root: Path, filename: String, contentType: String, bytes: ByteArray) {
    val relative = "dev/${email.messageId.substringBefore('@')}/$filename"
    val file = root.resolve(relative)
    Files.createDirectories(file.parent)
    Files.write(file, bytes)
    email.attachments.add(EmailAttachmentEntity().also {
      it.email = email
      it.filename = filename
      it.contentType = contentType
      it.sizeBytes = bytes.size.toLong()
      it.partPath = "2"
      it.storedPath = relative
      it.downloadedAt = email.processedAt
    })
  }

  /** A khal config listing the dev calendars, each with its own vdir. */
  private fun writeKhalConfig(file: Path, calendarsDir: Path) {
    val names = listOf("Family", "Kids school", "Sport", "Bills", "Old calendar")
    Files.createDirectories(file.parent)
    val sections = names.joinToString("\n") { name ->
      val dir = calendarsDir.resolve(name.lowercase().replace(' ', '-'))
      Files.createDirectories(dir)
      "[[$name]]\npath = $dir\n"
    }
    Files.writeString(
        file,
        "[calendars]\n$sections\n[locale]\nlocal_timezone = Australia/Melbourne\ndefault_timezone = Australia/Melbourne\n\n" +
            "[sqlite]\npath = ${file.parent.resolve("khal.db")}\n"
    )
  }

  /** A one-page PDF showing three lines of text. */
  private fun devPdf(vararg lines: String): ByteArray {
    val text = lines.withIndex().joinToString(" ") { (i, line) ->
      "BT /F1 ${if (i == 0) 20 else 14} Tf 72 ${720 - i * 32} Td (${line.replace("(", "\\(").replace(")", "\\)")}) Tj ET"
    }
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length ${text.length} >>\nstream\n$text\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    )
    val out = StringBuilder("%PDF-1.4\n")
    val offsets = mutableListOf<Int>()
    objects.forEachIndexed { i, body ->
      offsets += out.length
      out.append("${i + 1} 0 obj\n$body\nendobj\n")
    }
    val xref = out.length
    out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { out.append(String.format("%010d 00000 n \n", it)) }
    out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().toByteArray(Charsets.ISO_8859_1)
  }

  /** A small map-like picture. */
  private fun devPng(): ByteArray {
    val image = BufferedImage(480, 300, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color(0xEF, 0xED, 0xDC)
    g.fillRect(0, 0, 480, 300)
    g.color = Color(0x50, 0xFA, 0x7B)
    g.fillRoundRect(40, 60, 180, 120, 24, 24)
    g.color = Color(0x8B, 0xE9, 0xFD)
    g.fillOval(260, 120, 160, 120)
    g.color = Color(0x28, 0x2A, 0x36)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
    g.drawString("Melbourne Zoo", 150, 40)
    g.dispose()
    return ByteArrayOutputStream().use {
      ImageIO.write(image, "png", it)
      it.toByteArray()
    }
  }

  companion object {
    private val LOG = Logger.getLogger(DevData::class.java)
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
  }
}
