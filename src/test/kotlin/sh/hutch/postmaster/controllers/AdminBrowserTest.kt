package sh.hutch.postmaster.controllers

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import sh.hutch.postmaster.testsupport.BrowserTest

/** Browser tests of the admin pages */
abstract class AdminBrowserTest : BrowserTest() {
  fun open(path: String, width: Int = 1280, height: Int = 800): Page {
    val page = openPage(width = width, height = height)
    val response = page.navigate(path.removePrefix("/"))
    if (response == null || response.status() != 200) {
      throw AssertionError("$path failed: HTTP ${response?.status()}\n${response?.text()?.take(2000)}")
    }
    page.waitForLoadState(LoadState.NETWORKIDLE)
    page.waitForFunction("() => window.Postmaster !== undefined")
    return page
  }

  fun Page.testId(id: String) = locator("[data-testid=\"$id\"]")
}
