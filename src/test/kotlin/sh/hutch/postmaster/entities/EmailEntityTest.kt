package sh.hutch.postmaster.entities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.services.MessageIds

// CLAR Q13 revision: action_required is set when the email's text implies the
// user needs to do something, and never for emails of type Bill, including
// when the type becomes Bill later.
class EmailEntityTest {
  private val bill = TypeEntity().apply {
    systemKey = TypeEntity.BILL_KEY
    name = "Bill"
  }
  private val school = TypeEntity().apply { name = "Primary school" }

  @Test
  fun `an email can be flagged as needing action, with a description`() {
    val email = EmailEntity().apply { assignType(school) }

    email.flagActionRequired(true, "Sign the permission form by Friday")

    assertTrue(email.actionRequired)
    assertEquals("Sign the permission form by Friday", email.actionDescription)
  }

  @Test
  fun `the description is dropped when no action is needed`() {
    val email = EmailEntity()

    email.flagActionRequired(false, "Nothing to do")

    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `a bill is never flagged as needing action`() {
    val email = EmailEntity().apply { assignType(bill) }

    email.flagActionRequired(true, "Pay the bill")

    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `the flag is cleared when the type becomes Bill`() {
    val email = EmailEntity().apply { assignType(school) }
    email.flagActionRequired(true, "Pay the deposit")

    email.assignType(bill)

    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `changing to a type other than Bill keeps the flag`() {
    val email = EmailEntity()
    email.flagActionRequired(true, "Book a time")

    email.assignType(school)

    assertTrue(email.actionRequired)
    assertEquals("Book a time", email.actionDescription)
  }

  @Test
  fun `the flag can be cleared once the action is done`() {
    // CLAR Q13 revision: clearable in the UI and through MCP
    val email = EmailEntity()
    email.flagActionRequired(true, "Book a time")

    email.clearActionRequired()

    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `the message hash follows the Message-ID`() {
    // CLAR D7/D20 and docs/dev/contracts.md: message_hash is the hash of message_id
    val email = EmailEntity().apply { messageId = "abc@mail.example" }

    email.syncMessageHash()

    assertEquals(MessageIds.hash("abc@mail.example"), email.messageHash)
  }
}
