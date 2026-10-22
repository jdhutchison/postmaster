You are building a system that will help manage an email inbox.

By periodically checking the inbox using IMAP the system will parse the email to detect things like sender, subject, the number of any attachments, etc.

Based on this information a set of rules can be used to classify the email.

An LLM on llama.cpp can be used to help parse and classify the emails if programmatic parsing and the rules do not provide sufficient information to get the required data.

Once classified an email is then tested against a number of action rules to determine what actions are to be triggered by the email. These actions make do something with the message or perform some other action.

The process is:

read emails -> run classification rules -> use LLM if required to complete email parsing and classification -> run action rules -> apply actions from matching rules

This system will also have a user interface to allow the results to be used.

For use by agents there will also need to be a MCP to allow querying of emails and processing results.

Features and requirements
=========================

The requirements below are not exhaustive. Features or functionality not defined but implied in other sections of the design

Configuration
-------------

1.  There will be a page to view and modify configuration settings
2.  The configuration settings will all be editable with the values converting to texr fields when clicked on and then persisting once the field loses focus
3.  Encrypted configuration values will be displayed encypted but if their value is updated then the new value will be encrypted before being saved to the database
4.  Duration or frequency based settings must support full and simplified ISO 8601 duration strings, e.g. `PT1H` and `1H` or `1h`

Types
-----

1.  There must be a page to view all existing types
2.  Each row of the table of types will have icons on the right edge to edit or delete
3.  If the edit button is clicked then a modal dialog will pop up allowing editing of the type
4.  If the delete button is clicken then a dialog asking to confirm will be shown.
5.  If the delete is confirmed then the type is to be soft deleted, not removed from the database
6.  The Types page will have a way to start creating a new type
7.  To create a new type a modal dialog with the fields will pop up
8.  The colour of a calendar can be set directly as a RGB hex code or through an HTML colour picker
9.  The initial types will be:

type name		desription
Bill			A utility bill
Primary school		Message from Brunswick South
High school		Message from Princes Hill SC
Statement notification	Statement available for card or bank
Legal
Caloundra West 		Message about property
Ebay 			Ebay business
Payslip 		Payslips
Showfit 		Message about Jeremy's dancing

Calendars
---------

1.  There will be a page that lists all known calendars
2.  The table listing the calendars will show their name and colour
3.  Calendars can be edited, deleted or enabled or disabled through icons or controls on each row of the listing table
4.  There will be an option to add a new calendar
5.  When adding a new calendar or editing an existing one all fields are required except Durations
6.  The colour value of a calendar can be set through a HTML colour picker element to set the hex code. The hex code can also be set directly
7.  If the Duration field is used then it must be validated to ensure that it contains only a comma plus space separated list of simplified or full ISO 8601 duration strings

Prompts
-------

1.  There will be a list of prompts used by the system with the list displayed as a table
2.  Each row of the table will have the icons to delete or edit the prompt
3.  The list of prompts will provide a way to start creating a new prompt
4.  A pop-up model will request confirmation of any request to delete a prompt
5.  The initial prompts are specified in the `Prompts for using with the LLM` section

### Editing or creating a prompt

1.  All fields, name and prompt text, will be mandatory
2.  The prompt editing text field will be as big as the viewport allows without overflowing the viewport
3.  The prompt editing text field will support the marking up of the prompt text with the markdown system to be used
4.  A way to insert placeholders into the prompt template must be available. These placeholders are to be:
    1.  A list of the available calendars and their purposes
    2.  A list of events from an email with all details
    3.  The subject of the email
    4.  The sender of the email
    5.  The `plain/text` message text from the email
    6.  The HTML DOM of the `text/html` part of the email
    7.  Contents of HTML page loaded for email

Rules
-----

1.  There will be a page to view all classification rules
2.  There will be a page to view all action rules
3.  Each page will list all rules of that type. Each row will have an icon to edit the rule and delete the rule
4.  If a delete icon is clicked a pop-up dialog will ask the user to confirm the deletion
5.  If the edit icon is clicked then go to the page to edit a rule
6.  Each row of the list of rules on the page will have a toggle to enable or disable the rule. The state of the toggle visually must represent the state of the rule
7.  Each page will have a way to trigger creating a new rule of that type. The trigger to create a new rule will take the user to a new page to create the rule

### Creating a new rule

1.  Clicking on the trigger to create a new rule will go the Create new rule page
2.  The type of the rule - `classification` or `action` will not be configurable and will be implied by the which list the user was looking at previously
3.  The user must set a name and a description. The priority is optional but must be set to a positive integer if a value is entered. The default is zero.
4.  Users can add conditions to the rule on the page. There must be at least one condition for the rule to be valid but there is no limit on the conditions.
5.  A condition must have an attribute - one of `subject`, `sender`, `message`, `events`, `attachments`, `bill_url`, `type`, `link`
6.  A condition must have a type - one of `is`, `contains`, `starts_with`, `regex`, `has`, `is_present`
7.  The list of possible condition type applicable to each attribute are:

Type / attribute

subject

sender

message

events

attachments

bill_url

type

link

is

Y

Y

Y

Y

Y

contains

Y

Y

Y

Y

starts_with

Y

Y

Y

Y

Y

regex

Y

Y

Y

Y

Y

Y

Y

Y

has

Y

Y

Y

Y

8.  Actions can be added to rules. There must be at least one action for the rule to be valid but there can be multiple.
9.  An action will have a type and a value. The valid types are `set_type`, `move`, `check_for_events`, `download_attachments`, `set_reply_needed`, `fetch_link_contents`, `create_events`, `skip_processing`, `mark_read`, `mark_unread`, `process_bill`, `forward_email`, `delete`
10.  For `move` and `forward_email` a destination value can be entered which has the label 'to:'. The value must be a non-empty string for destination.
11.  For `set_type` actions a classification type must be specified from the list of defined types

### Editing a rule

1.  When an edit icon is clicked or pressed on in a view rule page the interface will go to a edit page for that rule
2.  The name cannot be edited
3.  The description is editable
4.  The rule can be enabled or disabled
5.  There must be a way to delete the rule from the edit page, triggering a soft delete
6.  Conditions can be removed or edited
7.  There must be at least one condition for the rule
8.  New conditions can be added. The validation for new conditions must match the those rules specified for a new action
9.  Actions can be removed or edited
10.  Each action can be enabled or disabled
11.  There must be at least one action for the rule
12.  New actions can be added to the rule.
13.  The validation and options for adding or editing a new action will be the same for adding an action to a new rule

Viewing emails
--------------

1.  A page listing emails processed is required. By default the most recent thirty emails processed will be shown.
2.  The page will allow searching or filtering by:
    1.  Date range
    2.  Status
    3.  Type
    4.  Sender
    5.  Response required AND action required
    6.  Emails with processing errors
3.  When filtering or searching emails the results will be paginated if there are more than 50 results into pages of 50 or less
4.  The information to be displayed for each email in the emails page is:
    1.  Received
    2.  Subject
    3.  Sender
    4.  Status
    5.  Type
    6.  If a response is required or action needed
    7.  Count of events
    8.  Count of attachments
    9.  Count of errors, if any. Show nothing if none
5.  Clicking on an email will go to a page showing the full details of the email
6.  Rows with errors will be highlighted in a colour like red

### Viewing an individual email

1.  The view an email page will display all details of an email with the exception (if present in the email) of the following fields which will be only be visible if that field is clicked on (hidden in a togglable concertina component initially):
    1.  Full message text
    2.  Full text of any linked to page
2.  Events for the email will be shown with colour coding to reflect which calendar they were created in
3.  The calendar used for an event can be changed if the event is clicked on. A pop-up dialog will contain the list of available calendars and selecting one means the event must be moved calendars (or deleted from one and recreated in the other).
4.  The pop-up calendar dialog will have a cancel button to close it and not take any action
5.  The matching classification rule (if there is one) will be listed, along with the associated actions
6.  The matching action rules (if any) will be listed, along with the actions each matching rule triggered
7.  There will be a button to delete the email
8.  There will be a button to move the email to a different IMAP folder
9.  There will be a button to mark the email as unread
10.  The list of attachments will have an icon next to each downloaded attachment to allow it to be downloaded and viewed.
11.  If an attachment has been downloaded and is a type that can be previewed in a browser (in a modal dialog) then icon must be next to the name that if clicked will allow the document to be previewed in the application's UI

Processing emails
-----------------

The email processing will have four phases:

1.  Fetching the email from Dovecot
2.  Running Classification rules on email
3.  Clarifying any details using LLM
4.  Running action rules on the email

### Fetching emails

Email processing will happen at a frequency as configured in the application config. The default value for the frequency is to be 1h.

When it runs the application must look at all the emails in the inbox from most recently received to least and stop when it finds an email it has processed before OR it has processed all emails in the inbox.

The application will use IMAP to connect to Dovecot. Connection details will be stored in the application configuration. The password will be AES encrypted.

### Classifying emails

1.  Apply all enabled rules with the type `classification` in priority order (with higher priority coming first)
2.  Test each rule to see if it matches
3.  For the first rule that matches perform or arrange all the actions that are attached to that rule
4.  Stop applying rules after one classification rule matches
5.  Record all rules matching and actions taken

### Using LLM to clarify any details

1.  If an email is not classified with a type after all enabled rules are applied then use the `Classify and summarise` prompt with the LLM to classify and summarise the email. If it has a classification type then use the LLM with the `Summarise` prompt to generate the summary
2.  If the classification phase triggered a `fetch_link_contents` then fetch the contents of the identified URL

### Post classification and actions phase

Once the email has been classified and all preliminary actions have been

Action implementations
----------------------

Definitions of what the actions mean

### set_type

Set the classification type on the email to the `type` value from the action.

### move

Move the email to the folder with the name taken from the `destination` value of the action

### download_attachments

Download any non-filtered out attachments to the configured location and make then available to download through the user interface when the email they were attached to is viewed

### set_reply_needed

Set the `response_required` flag on the email record to `true`

### fetch_link_contents

If a link is present in the main contents of the email then open the link, fetch it's contents and summarise the contents. Both the whole contents and the summary are then saved against the email record.

### create_events

If there are no events associated with the email then attempt to identify any explicitly through the use of the LLM and the `identify_events` prompt.

If the events are already identified and associated with the email then use the assigned calendar from the action's `destination` to create events against the right calendar using Khal. See https://khal.readthedocs.io/en/v0.3/usage.html for how to use Khal.

When creating events add notifications if the `notifications` value of the action is populated with one or more durations.

### skip_processing

Stop any processing of the email regardless of the stage or type of rule the action was triggered with.

### mark_read

Mark the email as read using IMAP

### mark_unread

Mark the email as not read using IMAP

### process bill

Analyse the email to identify the Biller, the amount due, the date it is due by, a link to payment and to download the bill as an attachment or by following a link.

If the email is not classified as a `bill` then do nothing.

Otherwise look for the amount by using regexes to match text to the pattern `\$(\d+\.\d{0,2})` and parsing the group. Look for the due date by looking for text matching `(((Due|Pay) by)|due date).*?` followed by a date formatted as `dd MMM yyyy` such as `01 Oct 2026` or `25 May 2026`. The day part may or may not have a leading zero and the month may be a three letter abbreviation or the full name, capitalised or uncapitalised.

If there is no PDF attached then look for the bill link. The link to pay will have the anchor text "Pay now" or something similar. Search the DOM for it anchor tags with that text.

If ay information is missing you can pass the HTML message body to the LLM using the `Checking for the bill details` prompt.

Once the details have been identified:

1.  Set a reminder in the calendar by creating an event for the day before the bill is due. Include the biller, amount and the link to click on for payment in the notes of the event
2.  Download the bill, either from the email itself OR by following the link

### forward_email

Forward the email to the address in the `destination` value of the action

### delete

Use IMAP to delete the email and move it to Trash

MCP server
----------

The application must contain an MCP server for use by Hermes.

The MCP server must allow querying of processed emails and return there details: and allow actions on an individual email such as:

-   setting the type
-   moving
-   marking as read or unread
-   deleting
-   forwarding

The MCP server is for use by Hermes and the Postmaster profile.

Technical choices
=================

The application is to be written in Kotlin using the Quarkus framework.

The database will be MySQL and will be available at a configurable location.

The schema can be managed by Flyway or Liquibase, I have no preference. Whatever tool is selected should run as the application starts up.

The build tool is to be Gradle.

The JVM is to be GraalVM and for production usage Graal is to compile a native binary. Both local development and production will be on Ubuntu based machines. Production will be using ARM based CPUs.

The program needs to take one command line argument, the location of the configuration file.

In production the application will be deployed on Docker or Podman. I prefer Podman however external services will only be available on a Docker network so the choice must support that. The container needs to mount:

-   The khal configuration location
-   the directory where calendar data is located
-   the directory where attachments are downloaded to
-   the YAML configuration file needed for the server to start up

For now authentication will be handled external to the application. No authorisation is currently needed. Any user with access will have full access for now.

The user interface is to be statically rendered. HTMX can be used for dynamic UI updates where appropriate.

Email will be checked using IMAP using a connection to a Dovecot server.

For testing an in-memory database should be used with a known data set and the stubbing of external dependencies such as the LLM and Dovecot. A set of integration tests that test through HTTP requests and responses primarily. The user interface should be tested thoroughly.

The actions should have an interface to make new action types available. There should be a set of reusable functions for common action tasks such as:
- Parse a DOM
- Download a page or file over HTTP(s)
- Call the LLM
- Interact with Dovecot to manipulate emails
and anything else common to multiple actions

Output artifacts
----------------

The expected outputs are:

-   The codebase of Kotlin code plus tests, basic configuration file and the Gradle build file
-   The Dockerfile for the container definition
-   Any shell scripts required for building or deploying, to be placed in `bin/`
-   Any basic documentation, such as on the architecture or how to run testing is to be written to `docs/`
    -   This includes an openapi.yml spec file. This should be generated from annotations on the controllers (or whatever is used to implement the presentation layer)

These are to be written to `/data/code/postmaster` and any specified directories are to be subdirectories of that one.

Additionally produce an Ansible playbook and all supporting files needed to deploy the application to tigerbear. This includes:

-   Nginx configuration so that the application can be accessed from https://hutch.sh/mail. Basic authentication must be applied to all traffic.
-   Systemd service configuration
-   Any other configuration for production

The container must have khal loaded onto it for scheduling calendar events. It must also have curl and a headless browser that simulates Chrome running on Windows in the user agent for downloading content.

Coding style
------------

Database tables should be named after the thing they represent, in Snake Case with the first letter capitalised, eg. `Rules` or `Emails`. Columns are to in snake case but all lower case, e.g. `received_at`.

Indentation is to be two spaces. No tabs. Continuation indents to be four spaces.

Otherwise standard Kotlin coding style to be used.

The application is to be structured into Controllers for presentation logic, Services for "business" logic and Repositories for data access and querying. However you can recommend a different structure if you want.

Classes or objects that represent data in the database are to be called `<table noun>Entity`, for example `RuleEntity`. If data needs to be changed, structure wise, then these should be models, placed in a `models` package, named after the thing they for example `Rule`. Explicit Model classes should only be used where needed otherwise use entities.

Model classes can also be used for serialising and deserialising JSON when communicating to the LLM or where any request or response does not have a suitable representation in existing Entity or model form.

MapStruct should be used for mapping between the two as needed.

User interface style
--------------------

The layout must be responsible and work as well as possible on a mobile screen as well as a desktop screen.

The header row for tables must be sticky when they are too big to fit in the viewport so that the header row is always visible when the table is visible. Table columns can be resized and when text overflows the width then it is truncated. Hovering over that text will bring up a tool tip with the full contents.

Tables can reduce the number of columns to be reactive, with the

All menu options must be in a single menu that is accessible by clicking a hamburger menu button. The background of that button should be frosted glass so it "floats" over the rest of the contents.

The design is to be a model, flat, style. The palette should have light and dark modes and the mode must match the system mode of the device viewing it. Darcula and Alucard should be used as the basis for the dark and light palettes.

Atkinson Hyperledgible Next is to be used for the font.

Timestamps for when something was received are to be rendered as relative values, e.g. 14m ago, 2d ago. Days is the largest level of granularity.

Toasts are to be used to communicate successful changes or errors. Success confirmation toasts will fade after an appropriate time, error message toasts must be explicitly dismissed or closed.

Configuration
-------------

The configuration file will only contain configuration needed for the app to start up. This includes:

-   Database connection details
-   Configuration encryption key
-   Path to khal configuration file (see https://khal.readthedocs.io/en/v0.3/configure.html)

Other configuration details should be stored in the database. Some of these values should be stored encrypted, their value in the DB needs to be prefixed with `enc:`. The configuration values in the database should include:

-   Address of Dovecot server
-   Username for Dovecot
-   Password for Dovecot (AES encrypted)
-   Address for the LLM instance
-   API key for the LLM instance (AES encrypted if set)
-   Path to save PDF attachments
-   Attachment extensions to process (a comma separated list)

Database schema/entities
------------------------

These schema and table definitions are to be considered a starting basis for the end schema. They can be adapted based on the rest of this document.

### Settings

The `Settings` table is for configuration settings and should have the columns:

-   id
-   key
-   label
-   value

### Types

`Types` stores the category types that emails can be classified as. Columns:

-   id
-   name
-   description
-   colour

### Calendars

`Calendars` lists the known calendars and details about them

-   id
-   name - matching the name in the khal configuration
-   description - what the calendar is for and what events this calendar should be used for
-   colour - A hexidecimal RGB colour code to use when rendering the calendars
-   enabled - boolean, if a calendar should be used in email processing, true by default
-   notification_periods - A comma separated list of strings with simplified duration definitions (e.g. 4h, 1d) of when notifications need to be added for an event.

### Rules

`Rules` will store the classification and action rules.

-   id
-   name
-   type, the type of rule, one of `classification` or `action`
-   priority - int, a number that sets what priority the rule is applied
-   enabled - true or false
-   deleted - true or false. If true is soft deleted
-   created_at - timestamp when created
-   last_updated_at - timestamp when last updated
-   deleted_at - timestamp when (soft) deleted

### Rule_Actions

What actions a rule can trigger

-   id
-   rule_id
-   action_type - what the action is, enum, one of `set_type`, `move`, `check_for_events`, `download_attachments`, `set_reply_needed`, `fetch_link_contents`, `create_events`, `skip_processing`, `mark_read`, `mark_unread`, `process_bill`, `forward_email`, `delete`
-   destination - where to move the email to if the action is `move` or the calendar to use if the action is `create_events` or an email address to forward the email to if the action is `forward_email`
-   type - the id of the type to classify an email as if the `action_type` is `set_type`
-   enabled - true or false
-   deleted - true or false. If true is soft deleted
-   created_at - timestamp when created
-   last_updated_at - timestamp when last updated
-   deleted_at - timestamp when (soft) deleted

### Conditions

`Rules` have one or more `Conditions` to see if it is applicable. Columns:

-   id
-   rule_id
-   attribute - enum for what attribute of an email to check. One of `sender`, `subject`, `events`, `attachments`, `type`
-   match_type - enum: one of `is`, `contains`, `starts_with`, `regex`, `is_present`, `has_any`
-   test - what the attribute must match (when evaluated against the match type)

### Emails

The `Emails` table stores the emails the system processes. Some columns are not normalised and can be turned into

-   id
-   subject
-   sender
-   type
-   message (long text)
-   summary (long text)
-   link
-   bill_sender - who is sending the bill, will be null if type is not `bill`
-   bill_due_date - ISO 8601 date string, will be null if type is not `bill`
-   bill_amount - float, the amount needed to pay, will be null if type is not `bill`
-   bill_pay_link - the URL to use to pay the bill
-   bill_download_url
-   response_required - boolean
-   moved_to - folder where email was moved to
-   attachments - all the attached filenames we're interested in
-   classification_rules - all the rules (rule ids) of all rules that matched for classification
-   action_rules - all the rules (rule ids) of all rules that matched for actioning
-   errors - List of errors that occurred while processing the email
-   received - timestamp of when email was received
-   processed_at - timestamp of when the email was processed

### Email_events

Details of events found in the body of an email

-   id
-   email_id
-   calendar - which calendar it was added to
-   title - title of the event
-   timestamp - when the event is
-   location - where the event is (can be null)
-   is_added - boolean, if the event has been saved to calendar
-   created_at - when the record was created
-   last_updated_at - when the record was last updated

### Prompts

Prompts used for calling the LLM

-   id
-   name
-   contents

Prompts for using with the LLM
==============================

These are the initial prompts to add

Summarising the email
---------------------

Analyse the text of this email to identify if there are any events (there many be zero, one or multiple) that can be added to a calendar, if there is a link to a page then extract the link. Summarise the contents of the email message. Attempt to identify if a response is required. Return the details as JSON with this structure:

    {
      "summary": "<summary of email>",
      "link": "<url of link>",
      "response_required": <true|false>,
      "events": [
        {
          "title": "<title of your event>"
          "when": "<string - ISO 8601 datetime without zone>",
          "location": "<where the event is, if it can be determined, leave null otherwise>"
          "notes": "<string> any other details worth noting"
        }
      ],
      "errors": [
        <list of strings specifying errors>
      ]
    }
    

Do not guess any details. Only the summary is mandatory; include the other fields only if values are detected. Report errors when processing email, if any.

Classifying and summarising email
---------------------------------

Select one of the following email types:

    <type name and description placeholder table>
    

For the following email:

    Sender: <sender placeholder>
    Subject: <subject placeholder>
    Contents: <plain text contents of message placeholder>
    

Answer with the name of a valid type or with the message "NO CLASSIFICATION" only. No other text like reasoning - only the name of the type or "NO CLASSIFICATION".

Selecting a calendar
--------------------

For each event in the following JSON list select one of these calendars that best matches the details of the event. Return a JSON map with the event ids used for the keys and the calendar name as the value.
Here are the calendars to select from:

    <table of enabled calendars placeholder>
    

Here are the events:

    <table of events placeholder>
    

If no calendar seems to match then do not return a recommendation. Return recommendations as a JSON map with the event ids as the keys and the recommended calendar name as the value.

Summarising contents of fetched page
------------------------------------

Summarise the contents of this webpage from its HTML. Most navigation elements should be removed but ignore any remaining. Here's the contents:

    <fetch page HTML contents placeholder>
    

Checking for the bill details
-----------------------------

For this email message:

    Sender: <sender placeholder>
    Subject: <subject placeholder>
    Contents: <html contents of message placeholder>
    

Identify the following attributes:

-   The organisation to pay
-   The date payment is due
-   The amount that is due
-   The link to do the payment
-   The URL to download the bill (this may or may not be present)

Return the results in JSON like this example:

    {
      "organisation": "Electric Light Compnay",
      "due_date": "2026-05-25",
      "amount_due": 123.45,
      "payment_link": "https://electriclight.co/pay/lwkjeflj32rwef",
      "bill_url": "https://electriclight.co/pay/lwkjeflj32rwef"
    }
    

Do not guess any details.


