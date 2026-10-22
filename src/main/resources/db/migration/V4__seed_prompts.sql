-- System prompts (REQUIREMENTS.md "Prompts for using with the LLM"), with the fixes in
-- CLARIFICATIONS.md D19 and the revised Summarise prompt in R1, plus a new identify_events prompt
-- that create_events uses. The code finds system prompts by prompt_key, so their names and
-- contents can be edited but they can't be deleted (Q15).
-- Placeholders: {{calendars}} {{events}} {{subject}} {{sender}} {{text}} {{html}} {{page}} {{types}}

INSERT INTO Prompts (prompt_key, name, contents, deleted, created_at, last_updated_at) VALUES
('summarise', 'Summarising the email',
'Analyse the text of this email to identify if there are any events (there may be zero, one or multiple) that can be added to a calendar, and if there is a link to a page then extract the link. Summarise the contents of the email message. Attempt to identify if a response is required. Also attempt to identify if the email asks or implies that the recipient needs to do something other than reply, such as complete or sign a form, make a booking or payment, provide information, or do something by a deadline. Return the details as JSON with this structure:

{
  "summary": "<summary of email>",
  "link": "<url of link>",
  "response_required": <true|false>,
  "action_required": <true|false>,
  "action": "<short description of what needs to be done, including any deadline>",
  "events": [
    {
      "title": "<title of your event>",
      "when": "<string - ISO 8601 datetime without zone>",
      "location": "<where the event is, if it can be determined, leave null otherwise>",
      "notes": "<string> any other details worth noting"
    }
  ],
  "errors": [
    <list of strings specifying errors>
  ]
}

Do not guess any details. Only the summary is mandatory; include the other fields only if values are detected. Report errors when processing email, if any.

Sender: {{sender}}
Subject: {{subject}}
Contents: {{text}}',
FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

('classify', 'Classifying and summarising email',
'Select one of the following email types:

{{types}}

For the following email:

Sender: {{sender}}
Subject: {{subject}}
Contents: {{text}}

Answer with the name of a valid type or with the message "NO CLASSIFICATION" only. No other text like reasoning - only the name of the type or "NO CLASSIFICATION".',
FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

('select_calendar', 'Selecting a calendar',
'For each event in the following JSON list select one of these calendars that best matches the details of the event. Return a JSON map with the event ids used for the keys and the calendar name as the value.
Here are the calendars to select from:

{{calendars}}

Here are the events:

{{events}}

If no calendar seems to match then do not return a recommendation. Return recommendations as a JSON map with the event ids as the keys and the recommended calendar name as the value.',
FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

('summarise_page', 'Summarising contents of fetched page',
'Summarise the contents of this webpage from its HTML. Most navigation elements should be removed but ignore any remaining. Here''s the contents:

{{page}}',
FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

('bill_details', 'Checking for the bill details',
'For this email message:

Sender: {{sender}}
Subject: {{subject}}
Contents: {{html}}

Identify the following attributes:

- The organisation to pay
- The date payment is due
- The amount that is due
- The link to do the payment
- The URL to download the bill (this may or may not be present)

Return the results in JSON like this example:

{
  "organisation": "Electric Light Company",
  "due_date": "2026-05-25",
  "amount_due": 123.45,
  "payment_link": "https://electriclight.co/pay/lwkjeflj32rwef",
  "bill_url": "https://electriclight.co/pay/lwkjeflj32rwef"
}

Do not guess any details.',
FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

('identify_events', 'Identifying events',
'Analyse the text of this email to identify if there are any events (there may be zero, one or multiple) that can be added to a calendar, such as appointments, meetings, excursions, performances, sessions or deadlines. Return the details as JSON with this structure:

{
  "events": [
    {
      "title": "<title of your event>",
      "when": "<string - ISO 8601 date, or datetime without zone>",
      "location": "<where the event is, if it can be determined, leave null otherwise>",
      "notes": "<string> any other details worth noting"
    }
  ],
  "errors": [
    <list of strings specifying errors>
  ]
}

Do not guess any details. If there are no events then return an empty list of events. Use a date without a time only for events that last all day. Report errors when processing email, if any.

Sender: {{sender}}
Subject: {{subject}}
Contents: {{text}}',
FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
