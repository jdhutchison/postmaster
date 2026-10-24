package sh.hutch.postmaster.services

// Failures talking to the outside world. Messages are fit to show to the user and to store against
// an email.

class MailboxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** [transient] when the LLM couldn't be reached or was busy, so asking again later may work. */
class LlmException(message: String, cause: Throwable? = null, val transient: Boolean = false) : RuntimeException(message, cause)

class FetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class CalendarException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TodoListException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
