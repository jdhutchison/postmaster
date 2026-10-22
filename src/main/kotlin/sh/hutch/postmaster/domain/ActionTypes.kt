package sh.hutch.postmaster.domain

/**
 * Names of the built-in action types, stored in `Rule_actions.action_type`. Action types are plain
 * strings so new ones can be registered without a schema change.
 */
object ActionTypes {
  const val SET_TYPE = "set_type"
  const val MOVE = "move"
  const val CHECK_FOR_EVENTS = "check_for_events"
  const val DOWNLOAD_ATTACHMENTS = "download_attachments"
  const val SET_REPLY_NEEDED = "set_reply_needed"
  const val FETCH_LINK_CONTENTS = "fetch_link_contents"
  const val CREATE_EVENTS = "create_events"
  const val SKIP_PROCESSING = "skip_processing"
  const val MARK_READ = "mark_read"
  const val MARK_UNREAD = "mark_unread"
  const val PROCESS_BILL = "process_bill"
  const val FORWARD_EMAIL = "forward_email"
  const val DELETE = "delete"

  /** All built-in action types, in the order the requirements list them. */
  val BUILT_IN: List<String> = listOf(
      SET_TYPE, MOVE, CHECK_FOR_EVENTS, DOWNLOAD_ATTACHMENTS, SET_REPLY_NEEDED, FETCH_LINK_CONTENTS,
      CREATE_EVENTS, SKIP_PROCESSING, MARK_READ, MARK_UNREAD, PROCESS_BILL, FORWARD_EMAIL, DELETE
  )
}
