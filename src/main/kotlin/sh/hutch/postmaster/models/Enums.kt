package sh.hutch.postmaster.models

/** Whether a rule classifies email or acts on it. */
enum class RuleType(override val code: String) : CodedEnum {
  CLASSIFICATION("classification"),
  ACTION("action");

  companion object {
    fun fromCode(code: String): RuleType = codedEnumOf(code)
  }
}

/** How a rule's conditions combine: all must match (AND) or any one may (OR) (CLARIFICATIONS.md R8). */
enum class ConditionJoin(override val code: String) : CodedEnum {
  ALL("all"),
  ANY("any");

  companion object {
    fun fromCode(code: String): ConditionJoin = codedEnumOf(code)
  }
}

/** The attribute of an email that a condition tests. */
enum class ConditionAttribute(override val code: String) : CodedEnum {
  SUBJECT("subject"),
  SENDER("sender"),
  MESSAGE("message"),
  EVENTS("events"),
  ATTACHMENTS("attachments"),
  BILL_URL("bill_url"),
  TYPE("type"),
  LINK("link");

  companion object {
    fun fromCode(code: String): ConditionAttribute = codedEnumOf(code)
  }
}

/**
 * How a condition tests an attribute. [appliesTo] is the compatibility matrix agreed in
 * CLARIFICATIONS.md Q9; [needsTest] is false for the match types that take no test value (Q10).
 */
enum class MatchType(
    override val code: String,
    val needsTest: Boolean,
    private val attributes: Set<ConditionAttribute>
) : CodedEnum {
  IS(
      "is", true,
      setOf(
          ConditionAttribute.SUBJECT, ConditionAttribute.SENDER, ConditionAttribute.BILL_URL,
          ConditionAttribute.TYPE, ConditionAttribute.LINK
      )
  ),
  CONTAINS(
      "contains", true,
      setOf(ConditionAttribute.SUBJECT, ConditionAttribute.SENDER, ConditionAttribute.MESSAGE, ConditionAttribute.LINK)
  ),
  STARTS_WITH(
      "starts_with", true,
      setOf(
          ConditionAttribute.SUBJECT, ConditionAttribute.SENDER, ConditionAttribute.MESSAGE,
          ConditionAttribute.BILL_URL, ConditionAttribute.LINK
      )
  ),
  REGEX("regex", true, ConditionAttribute.entries.toSet()),
  HAS("has", false, setOf(ConditionAttribute.EVENTS, ConditionAttribute.ATTACHMENTS)),
  IS_PRESENT(
      "is_present", false,
      setOf(
          ConditionAttribute.MESSAGE, ConditionAttribute.EVENTS, ConditionAttribute.ATTACHMENTS,
          ConditionAttribute.BILL_URL, ConditionAttribute.TYPE, ConditionAttribute.LINK
      )
  );

  fun appliesTo(attribute: ConditionAttribute): Boolean = attribute in attributes

  companion object {
    fun fromCode(code: String): MatchType = codedEnumOf(code)
  }
}

/**
 * How far processing an email has got: the last step saved (CLARIFICATIONS.md R6). An interrupted
 * email carries on from the step after it.
 */
enum class ProcessingProgress(override val code: String) : CodedEnum {
  /** The email is saved; nothing has been done with it. */
  RECORDED("recorded"),

  /** The classification rule has matched and its record actions have run. */
  CLASSIFIED("classified"),

  /** The LLM's classification and summary are saved. */
  SUMMARISED("summarised"),

  /** The matching action rules are saved; their actions run one at a time. */
  MATCHED("matched");

  companion object {
    fun fromCode(code: String): ProcessingProgress = codedEnumOf(code)
  }
}

/** Processing status of an email (CLARIFICATIONS.md D1). */
enum class EmailStatus(override val code: String) : CodedEnum {
  PENDING("pending"),
  PROCESSED("processed"),
  SKIPPED("skipped"),
  FAILED("failed");

  companion object {
    fun fromCode(code: String): EmailStatus = codedEnumOf(code)
  }
}

/**
 * Whether Postmaster still keeps track of an email (CLARIFICATIONS.md R9). Only [TRACKED] emails can
 * be acted on; the others can still be viewed.
 */
enum class EmailTracking(override val code: String, val label: String) : CodedEnum {
  TRACKED("tracked", "Tracked"),

  /** It's no longer in the folder it was expected in. */
  MOVED("moved", "Moved"),

  /** It's in the trash folder, or was deleted by Postmaster. */
  DELETED("deleted", "Deleted"),

  /** It was marked done: nothing more needs doing. */
  DONE("done", "Done");

  val isTracked: Boolean
    get() = this == TRACKED

  companion object {
    fun fromCode(code: String): EmailTracking = codedEnumOf(code)
  }
}

/** What an action item is for (CLARIFICATIONS.md R11). */
enum class ActionItemKind(override val code: String) : CodedEnum {
  /** Something the email asks the user to do, found by the LLM (R5). */
  ACTION("action"),

  /** Replying to an email that needs a response; it follows the response required flag. */
  REPLY("reply"),

  /** A task added by hand. */
  TASK("task");

  companion object {
    fun fromCode(code: String): ActionItemKind = codedEnumOf(code)
  }
}

/** Where an action item is in the to-do list (CLARIFICATIONS.md R5, R9). */
enum class TodoStatus(override val code: String) : CodedEnum {
  OPEN("todo"),
  IN_PROGRESS("in-progress"),
  DONE("done");

  companion object {
    fun fromCode(code: String): TodoStatus = codedEnumOf(code)
  }
}

/** The processing phase an email error happened in. MANUAL is an action run from the UI or MCP. */
enum class ErrorPhase(override val code: String) : CodedEnum {
  FETCH("fetch"),
  CLASSIFICATION("classification"),
  LLM("llm"),
  ACTION("action"),
  MANUAL("manual");

  companion object {
    fun fromCode(code: String): ErrorPhase = codedEnumOf(code)
  }
}

/** What triggered an action on an email. */
enum class ActionSource(override val code: String) : CodedEnum {
  RULE("rule"),
  UI("ui"),
  MCP("mcp");

  companion object {
    fun fromCode(code: String): ActionSource = codedEnumOf(code)
  }
}

/** Outcome of an action on an email. */
enum class ActionResultStatus(override val code: String) : CodedEnum {
  DONE("done"),
  SKIPPED("skipped"),
  FAILED("failed"),

  /**
   * An action that can't safely run twice (forward_email) is recorded as started before it runs, so
   * an interrupted one isn't run again (CLARIFICATIONS.md R6).
   */
  STARTED("started");

  companion object {
    fun fromCode(code: String): ActionResultStatus = codedEnumOf(code)
  }
}

/** What started a processing run. */
enum class RunTrigger(override val code: String) : CodedEnum {
  SCHEDULE("schedule"),
  MANUAL("manual");

  companion object {
    fun fromCode(code: String): RunTrigger = codedEnumOf(code)
  }
}

/** Status of a processing run. */
enum class RunStatus(override val code: String) : CodedEnum {
  RUNNING("running"),
  SUCCEEDED("succeeded"),
  FAILED("failed");

  companion object {
    fun fromCode(code: String): RunStatus = codedEnumOf(code)
  }
}

/** Type of a setting's value, which decides how it is validated and stored. */
enum class SettingType(override val code: String) : CodedEnum {
  TEXT("text"),
  INT("int"),
  BOOL("bool"),
  DURATION("duration"),
  ENCRYPTED("encrypted"),
  PATH("path"),
  LIST("list"),
  CHOICE("choice"),
  TIMEZONE("timezone"),
  URL("url");

  companion object {
    fun fromCode(code: String): SettingType = codedEnumOf(code)
  }
}
