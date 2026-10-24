package sh.hutch.postmaster.models

/**
 * What rule conditions test about an email: the attributes in CLARIFICATIONS.md Q9. [message] is
 * the plain text, [events] the event titles, [attachments] the attachment filenames and [type]
 * the type's name.
 */
data class EmailFacts(
    val subject: String? = null,
    val sender: String? = null,
    val senderName: String? = null,
    val message: String? = null,
    val events: List<String> = emptyList(),
    val attachments: List<String> = emptyList(),
    val billUrl: String? = null,
    val type: String? = null,
    val link: String? = null
)
