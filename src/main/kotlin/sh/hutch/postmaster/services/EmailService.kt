package sh.hutch.postmaster.services

import io.quarkus.panache.common.Page
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import org.jboss.logging.Logger
import sh.hutch.postmaster.actions.ActionRegistry
import sh.hutch.postmaster.actions.AttachmentStore
import sh.hutch.postmaster.actions.isTrash
import sh.hutch.postmaster.actions.location
import sh.hutch.postmaster.actions.markTrashed
import sh.hutch.postmaster.actions.moveTo
import sh.hutch.postmaster.entities.EmailActionResultEntity
import sh.hutch.postmaster.entities.EmailAttachmentEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailErrorEntity
import sh.hutch.postmaster.entities.EmailEventEntity
import sh.hutch.postmaster.models.ActionItemView
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionResultView
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.AttachmentView
import sh.hutch.postmaster.models.BillDetails
import sh.hutch.postmaster.models.CalendarEvent
import sh.hutch.postmaster.models.CalendarOption
import sh.hutch.postmaster.models.EmailDetail
import sh.hutch.postmaster.models.EmailFilter
import sh.hutch.postmaster.models.EmailPage
import sh.hutch.postmaster.models.EmailRow
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.EmailTracking
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.ErrorView
import sh.hutch.postmaster.models.EventView
import sh.hutch.postmaster.models.ManualResult
import sh.hutch.postmaster.models.RuleResults
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.EmailEventRepository
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.util.Reminders
import sh.hutch.postmaster.util.Mdc
import sh.hutch.postmaster.util.Times
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Finds processed emails and carries out actions on them by hand, from the UI or MCP
 * (REQUIREMENTS.md "Viewing emails" and "MCP server"). Each manual action is recorded against the
 * email; a failure is recorded as a `manual` error and reported in the [ManualResult]. Only tracked
 * emails can be acted on (CLARIFICATIONS.md R9): an action on another fails with an
 * [OperationNotAllowedException], and nothing is recorded.
 */
@ApplicationScoped
class EmailService(
    private val emails: EmailRepository,
    private val types: TypeRepository,
    private val calendars: CalendarRepository,
    private val mailbox: MailboxClient,
    private val calendar: CalendarClient,
    private val settings: SettingsService,
    private val store: AttachmentStore,
    private val processing: ProcessingService,
    private val registry: ActionRegistry,
    private val todos: TodoListService,
    private val folders: FolderService,
    private val eventRepository: EmailEventRepository
) {

  /**
   * Emails matching [filter], newest first, in pages of [PAGE_SIZE]: within its last days if it has
   * them (R10). With no filters and no days, the [RECENT] most recent. Only tracked emails, unless
   * the filter includes the others (R9).
   */
  @Transactional
  fun search(filter: EmailFilter): EmailPage {
    val where = mutableListOf("e.deleted = false")
    val params = mutableMapOf<String, Any>()
    if (!filter.includeUntracked) {
      where += "e.tracking = :tracking"
      params["tracking"] = EmailTracking.TRACKED
    }
    val zone = settings.getZoneId(SettingKeys.PROCESSING_TIMEZONE)
    filter.from?.let {
      where += "e.receivedAt >= :from"
      params["from"] = it.atStartOfDay(zone).toInstant()
    }
    filter.to?.let {
      where += "e.receivedAt < :to"
      params["to"] = it.plusDays(1).atStartOfDay(zone).toInstant()
    }
    filter.lastDays?.let {
      where += "e.receivedAt >= :since"
      params["since"] = Times.now().minus(Duration.ofDays(it.toLong()))
    }
    filter.status?.let {
      where += "e.status = :status"
      params["status"] = it
    }
    when (filter.typeId) {
      null -> {}
      EmailFilter.UNCLASSIFIED -> where += "e.type is null"
      else -> {
        where += "e.type.id = :typeId"
        params["typeId"] = filter.typeId
      }
    }
    filter.sender?.trim()?.takeIf { it.isNotEmpty() }?.let {
      where += "(lower(e.sender) like :sender escape '!' or lower(e.senderName) like :sender escape '!')"
      params["sender"] = "%" + likeEscape(it.lowercase()) + "%"
    }
    if (filter.responseRequired) {
      where += "e.responseRequired = true"
    }
    if (filter.actionRequired) {
      where += "e.actionRequired = true"
    }
    if (filter.withErrors) {
      where += "exists (select 1 from EmailErrorEntity x where x.email = e)"
    }
    val query = emails.find("from EmailEntity e where ${where.joinToString(" and ")} order by e.receivedAt desc, e.id desc", params)
    if (!filter.isPaginated) {
      val list = query.page(Page.ofSize(RECENT)).list()
      return EmailPage(rows(list), filter, list.size.toLong(), 1, 1, paginated = false)
    }
    val total = query.count()
    val pageCount = max(1, ((total + PAGE_SIZE - 1) / PAGE_SIZE).toInt())
    val page = filter.page.coerceIn(1, pageCount)
    val list = query.page(Page.of(page - 1, PAGE_SIZE)).list()
    return EmailPage(rows(list), filter.copy(page = page), total, page, pageCount, paginated = true)
  }

  /** An active email. Call within a transaction. */
  fun get(id: Long): EmailEntity = emails.findActive(id) ?: throw NotFoundException("Email $id not found")

  /**
   * An active email that's still tracked, so it can be acted on (R9). Call within a transaction.
   *
   * @throws OperationNotAllowedException if it isn't tracked
   */
  private fun tracked(id: Long): EmailEntity = get(id).also { requireTracked(it) }

  private fun requireTracked(email: EmailEntity) {
    when (email.tracking) {
      EmailTracking.TRACKED -> {}
      EmailTracking.DONE -> throw OperationNotAllowedException("The email is done; reopen it to do anything more with it")
      else -> throw OperationNotAllowedException(
          "The email is no longer tracked (${email.tracking.label.lowercase()}), so nothing more can be done with it"
      )
    }
  }

  /** Whether an active email with this id exists. */
  @Transactional
  fun exists(id: Long): Boolean = emails.findActive(id) != null

  /** Refuses an email that isn't tracked (R9). */
  @Transactional
  fun requireTracked(id: Long) = requireTracked(get(id))

  /** An active email in full: its events, attachments, matching rules and the actions they ran. */
  @Transactional
  fun detail(id: Long): EmailDetail {
    val email = get(id)
    val byRule = email.actionResults.filter { it.source == ActionSource.RULE }.groupBy { it.rule?.id }
    val matches = email.ruleMatches.map { match ->
      RuleResults(match.rule?.id, match.rule?.name ?: "(deleted rule)", match.ruleType, byRule[match.rule?.id].orEmpty().map { view(it) })
    }
    val bill = if (email.billSender != null || email.billAmount != null || email.billDueDate != null || email.billPayLink != null ||
        email.billDownloadUrl != null) {
      BillDetails(email.billSender, email.billAmount, email.billDueDate, email.billPayLink, email.billDownloadUrl)
    } else {
      null
    }
    return EmailDetail(
        id = email.id!!,
        messageId = email.messageId,
        subject = email.subject,
        sender = email.sender,
        senderName = email.senderName,
        receivedAt = email.receivedAt,
        processedAt = email.processedAt,
        status = email.status,
        typeId = email.type?.id,
        type = email.type?.name,
        typeColour = email.type?.colour,
        folder = email.folder,
        movedTo = email.movedTo,
        isRead = email.isRead,
        summary = email.summary,
        link = email.link,
        linkSummary = email.linkSummary,
        linkContents = email.linkContents,
        message = email.message,
        responseRequired = email.responseRequired,
        actionRequired = email.actionRequired,
        actionDescription = email.actionDescription,
        actionItems = email.actionItems.map {
          ActionItemView(it.id!!, it.description, it.dueDate, it.notes, it.completed, it.completedAt, it.todoName, it.inProgress)
        },
        tracking = email.tracking,
        trackingChangedAt = email.trackingChangedAt,
        bill = bill,
        events = email.events.map { view(it) },
        attachments = email.attachments.map { view(it) },
        classification = matches.filter { it.ruleType == RuleType.CLASSIFICATION },
        actions = matches.filter { it.ruleType == RuleType.ACTION },
        manual = email.actionResults.filter { it.source != ActionSource.RULE }.map { view(it) },
        errors = email.errors.map { ErrorView(it.phase, it.message, it.createdAt) },
        calendars = calendars.listEnabled().map { CalendarOption(it.id!!, it.name, it.colour) }
    )
  }

  /** Sets the email's type, or clears it when [typeId] is null. Setting Bill clears action required (Q13). */
  @Transactional
  fun setType(id: Long, typeId: Long?, source: ActionSource): ManualResult {
    val email = tracked(id)
    val type = typeId?.let { types.findActive(it) ?: throw ValidationException(mapOf("typeId" to "Choose a type")) }
    return manual(email, ActionTypes.SET_TYPE, source) {
      email.assignType(type)
      if (type == null) "Type cleared" else "Type set to ${type.name}"
    }
  }

  /** Moves the message to [folder] in the mailbox. Moving it to the trash folder deletes it (R9). */
  @Transactional
  fun move(id: Long, folder: String?, source: ActionSource): ManualResult {
    val email = tracked(id)
    val destination = folder?.trim()?.takeIf { it.isNotEmpty() } ?: throw ValidationException(mapOf("folder" to "Choose a folder"))
    return manual(email, ActionTypes.MOVE, source) {
      email.moveTo(destination, mailbox)
      if (settings.isTrash(destination)) {
        email.markTrashed()
      }
      "Moved to $destination"
    }
  }

  /** Marks the message read or unread in the mailbox. */
  @Transactional
  fun setRead(id: Long, read: Boolean, source: ActionSource): ManualResult {
    val email = tracked(id)
    return manual(email, if (read) ActionTypes.MARK_READ else ActionTypes.MARK_UNREAD, source) {
      val (folder, uid) = email.location(mailbox)
      mailbox.setRead(folder, uid, read)
      email.isRead = read
      if (read) "Marked read" else "Marked unread"
    }
  }

  /**
   * Moves the message to the trash folder. The email is kept, so it isn't processed again
   * (CLARIFICATIONS.md D11), and is no longer tracked: it can still be viewed (R9).
   */
  @Transactional
  fun delete(id: Long, source: ActionSource): ManualResult {
    val email = tracked(id)
    return manual(email, ActionTypes.DELETE, source) {
      val trash = settings.getString(SettingKeys.IMAP_TRASH_FOLDER)?.takeIf { it.isNotBlank() }
          ?: throw IllegalStateException("The trash folder (imap.trash_folder) isn't set")
      email.moveTo(trash, mailbox)
      email.markTrashed()
      "Moved to $trash"
    }
  }

  /** Forwards the message to [to] (CLARIFICATIONS.md Q7). */
  @Transactional
  fun forward(id: Long, to: String?, source: ActionSource): ManualResult {
    val email = tracked(id)
    val address = to?.trim()?.takeIf { it.isNotEmpty() } ?: throw ValidationException(mapOf("to" to "Enter an address to forward to"))
    return manual(email, ActionTypes.FORWARD_EMAIL, source) {
      val (folder, uid) = email.location(mailbox)
      mailbox.forward(folder, uid, address)
      "Forwarded to $address"
    }
  }

  @Transactional
  fun setResponseRequired(id: Long, required: Boolean, source: ActionSource): ManualResult {
    val email = tracked(id)
    return manual(email, SET_RESPONSE_REQUIRED, source) {
      email.responseRequired = required
      if (required) "Response required" else "Response no longer required"
    }
  }

  /**
   * Sets action required by adding an action item ([description], unless the email already has an
   * open item and no description is given), or clears it by marking every open item done, ticking
   * them off in the to-do list too. Bills never need action (Q13), so setting it on one fails.
   */
  @Transactional
  fun setActionRequired(id: Long, required: Boolean, description: String?, source: ActionSource): ManualResult {
    val email = tracked(id)
    return manual(email, SET_ACTION_REQUIRED, source) {
      if (required && email.type?.isBill == true) {
        throw IllegalStateException("Bills never need action; they're handled by process_bill")
      }
      if (required) {
        val text = description?.trim()?.ifEmpty { null }
        if (text != null || email.openActionItems().isEmpty()) {
          email.addActionItem(text ?: ProcessingService.DEFAULT_ACTION)
        }
        "Action required"
      } else {
        for (item in email.openActionItems()) {
          todos.complete(item)
          email.completeActionItem(item)
        }
        "Action no longer required"
      }
    }
  }

  /** Marks one of the email's action items done, and ticks it off in the to-do list if it's there (R5). */
  @Transactional
  fun completeActionItem(emailId: Long, itemId: Long, source: ActionSource): ManualResult {
    val email = tracked(emailId)
    val item = email.actionItems.firstOrNull { it.id == itemId } ?: throw NotFoundException("Action item $itemId not found")
    return manual(email, COMPLETE_ACTION_ITEM, source) {
      if (item.completed) {
        return@manual "'${item.description}' is already done"
      }
      todos.complete(item)
      email.completeActionItem(item)
      if (item.todoName == null) "Done: ${item.description}" else "Done: ${item.description}, and ticked off in the to-do list"
    }
  }

  /**
   * Marks the email done (R9): every open action item is done, and ticked off in the to-do list if
   * it's there; no reply is needed; and the email is no longer tracked.
   */
  @Transactional
  fun markDone(id: Long, source: ActionSource): ManualResult {
    val email = tracked(id)
    return manual(email, MARK_DONE, source) {
      val open = email.openActionItems()
      for (item in open) {
        todos.complete(item)
        email.completeActionItem(item)
      }
      email.responseRequired = false
      email.changeTracking(EmailTracking.DONE)
      "Done" + if (open.isEmpty()) "" else ", with ${open.size} action item${if (open.size == 1) "" else "s"} done"
    }
  }

  /** Tracks an email marked done again (R9). Its action items stay done. */
  @Transactional
  fun reopen(id: Long, source: ActionSource): ManualResult {
    val email = get(id)
    if (email.tracking != EmailTracking.DONE) {
      throw OperationNotAllowedException("Only an email marked done can be reopened")
    }
    return manual(email, REOPEN, source) {
      email.changeTracking(EmailTracking.TRACKED)
      "Reopened"
    }
  }

  /**
   * Makes an event that isn't in a calendar an action item, due on its day (R9). The item is written
   * to the to-do list if update_todo_list has run on the email.
   */
  @Transactional
  fun eventToActionItem(emailId: Long, eventId: Long, source: ActionSource): ManualResult {
    val email = tracked(emailId)
    val event = event(email, eventId)
    when {
      event.isAdded -> throw OperationNotAllowedException("'${event.title}' is already in a calendar")
      email.type?.isBill == true -> throw OperationNotAllowedException("Bills never have action items; they're handled by process_bill")
      email.actionItems.any { it.description.equals(event.title.trim(), ignoreCase = true) } ->
        throw OperationNotAllowedException("The email already has an action item '${event.title}'")
    }
    return manual(email, EVENT_TO_ACTION_ITEM, source) {
      val item = email.addActionItem(event.title, event.startsAt?.toLocalDate(), notes(event))
          ?: throw IllegalStateException("'${event.title}' can't be an action item")
      val listed = usesTodoList(email)
      if (listed) {
        try {
          todos.add(email, item)
        } catch (e: Exception) {
          email.removeActionItem(item)
          throw e
        }
      }
      email.events.remove(event)
      "'${event.title}' is now an action item" + if (listed) ", in the to-do list" else ""
    }
  }

  /**
   * Makes an open action item an event on [date], at [time] or all day (R9), taking it out of the
   * to-do list if it's there. With a [calendarId], the event is added to that calendar too.
   */
  @Transactional
  fun actionItemToEvent(
      emailId: Long,
      itemId: Long,
      date: LocalDate?,
      time: LocalTime?,
      calendarId: Long?,
      source: ActionSource
  ): ManualResult {
    val email = tracked(emailId)
    val item = email.actionItems.firstOrNull { it.id == itemId } ?: throw NotFoundException("Action item $itemId not found")
    if (item.completed) {
      throw OperationNotAllowedException("'${item.description}' is already done")
    }
    val day = date ?: throw ValidationException(mapOf("date" to "Choose the event's date"))
    val target = calendarId?.let { id ->
      calendars.findActive(id)?.takeIf { it.enabled } ?: throw ValidationException(mapOf("calendarId" to "Choose a calendar"))
    }
    return manual(email, ACTION_ITEM_TO_EVENT, source) {
      // Out of the to-do list first: if that fails, nothing has changed
      todos.remove(item)
      val event = EmailEventEntity().also {
        it.email = email
        it.title = item.description.take(500)
        it.startsAt = time?.let { at -> day.atTime(at) } ?: day.atStartOfDay()
        it.endsAt = time?.let { at -> day.atTime(at).plus(settings.getDuration(SettingKeys.EVENTS_DEFAULT_DURATION)) }
        it.allDay = time == null
        it.notes = item.notes
      }
      email.events.add(event)
      eventRepository.persist(event)
      email.removeActionItem(item)
      val made = "'${item.description}' is now an event" + if (item.todoName != null) ", and out of the to-do list" else ""
      if (target == null) {
        return@manual made
      }
      val uid = calendar.addEvent(
          target.name,
          CalendarEvent(
              CalendarClient.eventUid(email.id!!, event.id!!), event.title, event.startsAt!!, event.endsAt, event.allDay, null, event.notes,
              Reminders.parseListOrEmpty(target.notificationPeriods)
          )
      )
      event.calendar = target
      event.isAdded = true
      event.khalUid = uid
      "$made in ${target.name}"
    }
  }

  /** Whether update_todo_list has run on the email, so its action items belong in the to-do list. */
  private fun usesTodoList(email: EmailEntity): Boolean =
    email.actionResults.any {
      it.source == ActionSource.RULE && it.actionType == ActionTypes.UPDATE_TODO_LIST &&
          (it.status == ActionResultStatus.DONE || it.status == ActionResultStatus.SKIPPED)
    }

  /** An event's time, place and notes, as an action item's notes. */
  private fun notes(event: EmailEventEntity): String? =
    listOfNotNull(
        event.startsAt?.takeIf { !event.allDay }?.let { "At " + CLOCK.format(it) },
        event.location?.let { "Where: $it" },
        event.notes
    ).joinToString("\n").ifEmpty { null }

  /**
   * Puts an event in another calendar. An event already added is moved in khal; one that isn't is
   * added to the chosen calendar (REQUIREMENTS.md "Viewing an individual email" 3).
   */
  @Transactional
  fun changeEventCalendar(emailId: Long, eventId: Long, calendarId: Long?, source: ActionSource): ManualResult {
    val email = tracked(emailId)
    val event = event(email, eventId)
    val target = calendarId?.let { calendars.findActive(it) }?.takeIf { it.enabled }
        ?: throw ValidationException(mapOf("calendarId" to "Choose a calendar"))
    return manual(email, CHANGE_EVENT_CALENDAR, source) {
      val current = event.calendar
      if (event.isAdded && event.khalUid != null && current != null) {
        if (current.id == target.id) {
          return@manual "${event.title} is already in ${target.name}"
        }
        calendar.moveEvent(event.khalUid!!, current.name, target.name)
        event.calendar = target
        "Moved ${event.title} from ${current.name} to ${target.name}"
      } else {
        val alarms = Reminders.parseListOrEmpty(target.notificationPeriods)
        val uid = calendar.addEvent(
            target.name,
            CalendarEvent(CalendarClient.eventUid(email.id!!, event.id!!), event.title, event.startsAt!!, event.endsAt, event.allDay, event.location, event.notes, alarms)
        )
        event.calendar = target
        event.isAdded = true
        event.khalUid = uid
        "Added ${event.title} to ${target.name}"
      }
    }
  }

  /** Processes the email again from its message in the mailbox. */
  fun reprocess(id: Long): ManualResult = Mdc.with(Mdc.EMAIL to id, Mdc.ACTION to "reprocess") { reprocessEmail(id) }

  private fun reprocessEmail(id: Long): ManualResult {
    if (!exists(id)) {
      throw NotFoundException("Email $id not found")
    }
    requireTracked(id)
    return try {
      val email = processing.reprocess(id)
      if (email.status == EmailStatus.PENDING) {
        ManualResult(true, "The LLM couldn't be reached, so the next run will finish processing the email")
      } else {
        ManualResult(true, "Processed the email again")
      }
    } catch (e: NotFoundException) {
      throw e
    } catch (e: Exception) {
      LOG.warnf(e, "Reprocessing failed: %s", e.message)
      ManualResult(false, "Reprocessing failed: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  /** The mailbox folders, for moving: as last scanned, or scanned now if they never have been (R9). */
  fun folders(): List<String> = folders.names()

  /** Scans the mailbox folders again and returns them (R9). */
  fun refreshFolders(): List<String> = folders.scan()

  /** When the mailbox folders were last scanned. */
  fun foldersScannedAt(): Instant? = folders.lastScannedAt()

  /** A downloaded attachment of an active email and where its file is. */
  @Transactional
  fun attachmentFile(emailId: Long, attachmentId: Long): StoredFile {
    val email = get(emailId)
    val attachment = email.attachments.firstOrNull { it.id == attachmentId }
        ?: throw NotFoundException("Attachment $attachmentId not found")
    val stored = attachment.storedPath ?: throw NotFoundException("${attachment.filename} hasn't been downloaded")
    val root = store.root().toAbsolutePath().normalize()
    val file = root.resolve(stored).normalize()
    if (!file.startsWith(root) || !Files.isRegularFile(file)) {
      throw NotFoundException("The file for ${attachment.filename} is missing")
    }
    return StoredFile(view(attachment), mediaType(attachment), file)
  }

  // ---------------------------------------------------------------------------------------------

  private fun view(result: EmailActionResultEntity) =
    ActionResultView(result.actionType, registry.label(result.actionType), result.source, result.status, result.detail, result.executedAt)

  private fun view(attachment: EmailAttachmentEntity) =
    AttachmentView(
        attachment.id!!, attachment.filename, attachment.contentType, attachment.sizeBytes, attachment.storedPath != null,
        if (attachment.storedPath != null) previewKind(mediaType(attachment)) else null
    )

  private fun view(event: EmailEventEntity): EventView {
    val start = event.startsAt
    val time = when {
      start == null -> ""
      event.allDay -> DAY.format(start)
      else -> DAY.format(start) + ", " + CLOCK.format(start) + (event.endsAt?.let { "–" + CLOCK.format(it) } ?: "")
    }
    val calendar = event.calendar?.takeIf { !it.deleted }
    return EventView(
        event.id!!, event.title, event.startsAt, event.endsAt, event.allDay, event.location, event.notes, event.isAdded,
        calendar?.id, calendar?.name, calendar?.colour, time
    )
  }

  private fun event(email: EmailEntity, eventId: Long): EmailEventEntity =
    email.events.firstOrNull { it.id == eventId } ?: throw NotFoundException("Event $eventId not found")

  /** Runs a manual action on [email] and records it; failures are recorded rather than thrown. */
  private fun manual(email: EmailEntity, actionType: String, source: ActionSource, block: () -> String): ManualResult =
    Mdc.with(Mdc.EMAIL to email.id, Mdc.ACTION to actionType, Mdc.SOURCE to source.code) { runManual(email, actionType, source, block) }

  private fun runManual(email: EmailEntity, actionType: String, source: ActionSource, block: () -> String): ManualResult {
    val result = try {
      ManualResult(true, block())
    } catch (e: Exception) {
      if (e is ValidationException || e is NotFoundException) {
        throw e
      }
      LOG.warnf(e, "Manual action failed: %s", e.message)
      ManualResult(false, e.message ?: e.javaClass.simpleName)
    }
    if (result.ok) {
      LOG.infof("Manual action done: %s", result.message)
    }
    email.actionResults.add(EmailActionResultEntity().also {
      it.email = email
      it.actionType = actionType
      it.source = source
      it.status = if (result.ok) ActionResultStatus.DONE else ActionResultStatus.FAILED
      it.detail = result.message.take(MAX_DETAIL)
      it.executedAt = Times.now()
    })
    if (!result.ok) {
      email.errors.add(EmailErrorEntity().also {
        it.email = email
        it.phase = ErrorPhase.MANUAL
        it.message = "$actionType: ${result.message}".take(MAX_DETAIL)
        it.createdAt = Times.now()
      })
    }
    return result
  }

  private fun rows(list: List<EmailEntity>): List<EmailRow> {
    val ids = list.mapNotNull { it.id }
    val events = counts("EmailEventEntity", ids)
    val attachments = counts("EmailAttachmentEntity", ids)
    val errors = counts("EmailErrorEntity", ids)
    return list.map {
      EmailRow(
          it.id!!, it.receivedAt, it.subject, it.sender, it.senderName, it.status, it.type?.name, it.type?.colour,
          it.responseRequired, it.actionRequired, it.isRead, events[it.id] ?: 0, attachments[it.id] ?: 0, errors[it.id] ?: 0,
          it.tracking
      )
    }
  }

  private fun counts(entity: String, ids: List<Long>): Map<Long, Long> {
    if (ids.isEmpty()) {
      return emptyMap()
    }
    val result = emails.getEntityManager()
        .createQuery("select x.email.id, count(x) from $entity x where x.email.id in :ids group by x.email.id", Array<Any>::class.java)
        .setParameter("ids", ids)
        .resultList
    return result.associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
  }

  private fun likeEscape(text: String) = text.replace("!", "!!").replace("%", "!%").replace("_", "!_")

  companion object {
    private val LOG = Logger.getLogger(EmailService::class.java)

    /** How many emails the unfiltered list shows. */
    const val RECENT = 30

    /** Emails per page of filtered results. */
    const val PAGE_SIZE = 50

    const val SET_RESPONSE_REQUIRED = "set_response_required"
    const val SET_ACTION_REQUIRED = "set_action_required"
    const val COMPLETE_ACTION_ITEM = "complete_action_item"
    const val CHANGE_EVENT_CALENDAR = "change_event_calendar"
    const val MARK_DONE = "mark_done"
    const val REOPEN = "reopen"
    const val EVENT_TO_ACTION_ITEM = "event_to_action_item"
    const val ACTION_ITEM_TO_EVENT = "action_item_to_event"

    private const val MAX_DETAIL = 4000

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val PREVIEW_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /** The attachment's media type, from the message or else its file name. */
    fun mediaType(attachment: EmailAttachmentEntity): String =
      attachment.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
          ?.takeIf { it.contains('/') && it != "application/octet-stream" }
          ?: URLConnection.guessContentTypeFromName(attachment.filename)?.lowercase(Locale.ROOT)
          ?: "application/octet-stream"

    /**
     * How a media type is previewed in the browser: `pdf`, `image` or `text`; null if it can't be
     * shown safely (HTML and SVG could run script).
     */
    fun previewKind(mediaType: String): String? =
      when {
        mediaType == "application/pdf" -> "pdf"
        mediaType in PREVIEW_IMAGES -> "image"
        mediaType == "text/plain" -> "text"
        else -> null
      }
  }
}

/** A downloaded attachment's file. */
data class StoredFile(val attachment: AttachmentView, val mediaType: String, val path: Path)
