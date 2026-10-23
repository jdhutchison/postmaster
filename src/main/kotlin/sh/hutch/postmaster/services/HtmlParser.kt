package sh.hutch.postmaster.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import sh.hutch.postmaster.models.Link
import java.util.Locale

/** Reads HTML: its text, and its links. */
object HtmlParser {
  private val BLOCKS = setOf(
      "p", "div", "br", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
      "blockquote", "pre", "section", "article", "header", "footer", "hr", "dd", "dt"
  )

  /** The readable text: blocks and line breaks on their own lines, runs of blank lines collapsed. */
  fun text(html: String): String {
    val body = Jsoup.parse(html).body()
    body.select("script, style, noscript, template").remove()
    val out = StringBuilder()
    append(body, out)
    return out.toString()
        .lines()
        .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
  }

  private fun append(node: Node, out: StringBuilder) {
    when (node) {
      is TextNode -> out.append(node.wholeText.replace('\n', ' '))
      is Element -> {
        val block = node.normalName() in BLOCKS
        if (block) out.append('\n')
        node.childNodes().forEach { append(it, out) }
        if (block && node.normalName() != "br") out.append('\n')
        if (node.normalName() in setOf("td", "th")) out.append(' ')
      }
    }
  }

  /** Every link with an href, resolved against [baseUrl] when it is given. */
  fun links(html: String, baseUrl: String? = null): List<Link> {
    val document = if (baseUrl == null) Jsoup.parse(html) else Jsoup.parse(html, baseUrl)
    return document.select("a[href]").mapNotNull { anchor ->
      val href = if (baseUrl == null) anchor.attr("href") else anchor.absUrl("href").ifEmpty { anchor.attr("href") }
      href.trim().takeIf { it.isNotEmpty() }?.let { Link(label(anchor), it) }
    }
  }

  /**
   * Links whose text (or aria-label or title) contains any of [anchorTexts], ignoring case: those
   * matching the first text first, then the second, and so on, each in document order.
   */
  fun findLinks(html: String, anchorTexts: List<String>, baseUrl: String? = null): List<Link> {
    val all = links(html, baseUrl)
    val found = LinkedHashSet<Link>()
    for (text in anchorTexts.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }) {
      all.filter { it.text.lowercase(Locale.ROOT).contains(text) }.forEach { found += it }
    }
    return found.toList()
  }

  private fun label(anchor: Element): String {
    val text = anchor.text().trim()
    if (text.isNotEmpty()) return text
    return anchor.attr("aria-label").trim().ifEmpty { anchor.attr("title").trim() }
  }
}
