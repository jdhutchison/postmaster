package sh.hutch.postmaster.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// REQ process bill: "The link to pay will have the anchor text 'Pay now' or
// something similar. Search the DOM for it"; CLAR D9/D14 (configurable anchor
// texts); REQ Technical choices: a reusable function to parse a DOM
class HtmlParserTest {
  private val bill = """
      <html><body>
        <p>Your bill of <b>$123.45</b> is due<br>by 25 Nov 2026.</p>
        <p>Thanks</p>
        <a href="/account">My account</a>
        <a href="https://pay.example/abc"> PAY NOW </a>
        <a href="https://example.test/bill.pdf" aria-label="Download your bill"><img src="pdf.png"></a>
        <a href="/pay-online" title="Pay online"><img src="card.png"></a>
      </body></html>
  """.trimIndent()

  @Test
  fun `text keeps blocks and line breaks on separate lines`() {
    val text = HtmlParser.text(bill)

    assertTrue(text.contains("Your bill of $123.45 is due\nby 25 Nov 2026."), text)
    assertTrue(text.contains("\nThanks"), text)
    assertTrue(!text.contains("<"), text)
    assertTrue(!text.contains("\n\n\n"), "blank lines collapsed")
  }

  @Test
  fun `links are resolved against the base URL`() {
    val links = HtmlParser.links(bill, "https://electric.example/emails/1")

    assertEquals("https://electric.example/account", links.first { it.text == "My account" }.href)
    assertEquals(4, links.size)
  }

  @Test
  fun `links are found by anchor text ignoring case, in the order of the texts`() {
    val found = HtmlParser.findLinks(bill, listOf("Pay online", "Pay now"), "https://electric.example/")

    assertEquals(listOf("https://electric.example/pay-online", "https://pay.example/abc"), found.map { it.href })
  }

  @Test
  fun `an aria-label or title counts as the link's text`() {
    val found = HtmlParser.findLinks(bill, listOf("download your bill"))

    assertEquals(listOf("https://example.test/bill.pdf"), found.map { it.href })
  }

  @Test
  fun `no match gives no links`() {
    assertTrue(HtmlParser.findLinks(bill, listOf("Unsubscribe")).isEmpty())
  }
}
