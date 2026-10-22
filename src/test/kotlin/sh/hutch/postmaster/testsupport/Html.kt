package sh.hutch.postmaster.testsupport

import io.restassured.RestAssured.given
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/** GETs [path] (relative to the root path), checks it is a 200 HTML page, and parses it */
fun getHtml(path: String): Document {
  val response = given().accept("text/html").get(path)
  assertEquals(200, response.statusCode, "GET $path status")
  val contentType = response.contentType
  assertTrue(contentType.startsWith("text/html"), "GET $path content type was $contentType")
  return Jsoup.parse(response.body.asString())
}

/** Parses an HTML fragment, such as an HTMX response */
fun parseHtml(html: String): Document = Jsoup.parse(html)

/** The single element matching [css]; fails if there are none or several */
fun Element.one(css: String): Element {
  val matches = select(css)
  if (matches.size != 1) {
    fail<Unit>("Expected exactly one element matching '$css' but found ${matches.size}")
  }
  return matches.first()!!
}

/** Fails if any element matches [css] */
fun Element.none(css: String) {
  val matches = select(css)
  if (matches.isNotEmpty()) {
    fail<Unit>("Expected no element matching '$css' but found ${matches.size}: ${matches.first()!!.outerHtml()}")
  }
}

/** The element with `data-testid` [id] */
fun Element.testId(id: String): Element = one("[data-testid=\"$id\"]")

/** The trimmed text of each element matching [css] */
fun Element.texts(css: String): List<String> = select(css).map { it.text().trim() }
