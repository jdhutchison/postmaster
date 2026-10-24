package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.services.HtmlParser
import sh.hutch.postmaster.services.HttpFetcher
import sh.hutch.postmaster.services.LlmClient
import sh.hutch.postmaster.services.PromptRenderer

/** Fetches the page the email links to, and stores its text and the LLM's summary of it. */
@ApplicationScoped
class FetchLinkContentsHandler(
    private val fetcher: HttpFetcher,
    private val llm: LlmClient,
    private val prompts: PromptRenderer
) : ActionHandler {
  override val actionType = ActionTypes.FETCH_LINK_CONTENTS
  override val stage = ActionStage.GATHER
  override val order = 10

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val link = context.email.link?.takeIf { it.isNotBlank() } ?: return ActionOutcome.skipped("No link to fetch")
    val page = fetcher.fetchPage(link)
    context.email.linkContents = HtmlParser.text(page)
    context.email.linkSummary = llm.chat(prompts.render("summarise_page", EventFinder.promptContext(context.email).copy(page = page)))
    return ActionOutcome.done("Fetched $link")
  }
}
