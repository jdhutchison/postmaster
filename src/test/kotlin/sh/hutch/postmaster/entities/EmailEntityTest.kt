package sh.hutch.postmaster.entities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.services.MessageIds
import java.time.LocalDate

// CLAR Q13 revision: action_required is set when the email's text implies the
// user needs to do something, and never for emails of type Bill, including
// when the type becomes Bill later. CLAR R5: what needs doing is a list of
// action items, and the flag follows the items that aren't done.
class EmailEntityTest {
  private val bill = TypeEntity().apply {
    systemKey = TypeEntity.BILL_KEY
    name = "Bill"
  }
  private val school = TypeEntity().apply { name = "Primary school" }

  @Test
  fun `an action item flags the email as needing action`() {
    val email = EmailEntity().apply { assignType(school) }

    val item = email.addActionItem("Sign the permission form", LocalDate.of(2026, 10, 30), "The form is attached")

    assertTrue(email.actionRequired)
    assertEquals("Sign the permission form", email.actionDescription)
    assertEquals(LocalDate.of(2026, 10, 30), item!!.dueDate)
    assertEquals("The form is attached", item.notes)
    assertSame(email, item.email)
  }

  @Test
  fun `an email can have several action items, in order`() {
    val email = EmailEntity()

    email.addActionItem("Sign the form")
    email.addActionItem("Pay \$35")

    assertEquals(listOf("Sign the form", "Pay \$35"), email.actionItems.map { it.description })
    assertEquals(listOf(0, 1), email.actionItems.map { it.position })
    assertEquals("Sign the form; Pay \$35", email.actionDescription)
  }

  @Test
  fun `the same action item isn't added twice`() {
    // Reprocessing finds the same items again
    val email = EmailEntity()
    email.addActionItem("Sign the form")

    assertNull(email.addActionItem("  sign the FORM "))
    assertNull(email.addActionItem("   "))

    assertEquals(1, email.actionItems.size)
  }

  @Test
  fun `the flag follows the items that aren't done`() {
    // CLAR Q13 revision: clearable in the UI and through MCP
    val email = EmailEntity()
    val form = email.addActionItem("Sign the form")!!
    val pay = email.addActionItem("Pay")!!

    email.completeActionItem(form)

    assertTrue(email.actionRequired)
    assertEquals("Pay", email.actionDescription)
    assertTrue(form.completed)
    assertNotNull(form.completedAt)
    assertEquals(listOf(pay), email.openActionItems())

    email.completeActionItem(pay)

    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `a bill never has action items`() {
    val email = EmailEntity().apply { assignType(bill) }

    assertNull(email.addActionItem("Pay the bill"))

    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `the items are dropped when the type becomes Bill`() {
    val email = EmailEntity().apply { assignType(school) }
    email.addActionItem("Pay the deposit")

    email.assignType(bill)

    assertTrue(email.actionItems.isEmpty())
    assertFalse(email.actionRequired)
    assertNull(email.actionDescription)
  }

  @Test
  fun `changing to a type other than Bill keeps the items`() {
    val email = EmailEntity()
    email.addActionItem("Book a time")

    email.assignType(school)

    assertTrue(email.actionRequired)
    assertEquals("Book a time", email.actionDescription)
  }

  @Test
  fun `dropping the items keeps those in the to-do list`() {
    // So reprocessing never writes an item to the to-do list twice
    val email = EmailEntity()
    email.addActionItem("Book a time")
    email.addActionItem("Post the turntable")!!.todoName = "Post the turntable"

    email.dropActionItems()

    assertEquals(listOf("Post the turntable"), email.actionItems.map { it.description })
    assertTrue(email.actionRequired)
  }

  @Test
  fun `the message hash follows the Message-ID`() {
    // CLAR D7/D20 and docs/dev/contracts.md: message_hash is the hash of message_id
    val email = EmailEntity().apply { messageId = "abc@mail.example" }

    email.syncMessageHash()

    assertEquals(MessageIds.hash("abc@mail.example"), email.messageHash)
  }
}
