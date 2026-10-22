# Testing Postmaster

Tests are written from REQUIREMENTS.md and CLARIFICATIONS.md, not from the implementation. Each test names the requirement (`REQ …`) or clarification (`CLAR …`) it checks, in its name or a comment.

## Running the tests

Run every test with:

```sh
./gradlew test
```

- Reports go to `build/reports/tests/test/index.html`.
- Raw results are in `build/test-results/test/`.

To run one class or one test:

```sh
./gradlew test --tests '*SettingsServiceTest'
./gradlew test --tests '*SettingsServiceTest.a list is stored*'
```

**Parallel builds.** When two builds share this working tree (the builder and tester agents), the tester builds into its own directory so the two don't overwrite each other:

```sh
POSTMASTER_BUILD_DIR=build-tests ./gradlew --project-cache-dir .gradle-tests test
```

**Toolchain.** The project builds with GraalVM for JDK 25 and Gradle 9. With SDKMAN, run `source ~/.sdkman/bin/sdkman-init.sh` first.

## What the tests run against

The requirements ask for an in-memory database, a known data set, and stubs for external services.

| Production | In tests |
|---|---|
| MariaDB | H2 in-memory database in MariaDB mode, with case-sensitive identifiers like MariaDB on Linux (`src/test/resources/application.yaml`). Flyway runs the real migrations at startup and cleans the database first. |
| Dovecot (IMAP) and the SMTP relay | GreenMail, on dynamic ports (`GreenMailTestResource`) |
| llama.cpp and web pages | WireMock, on a dynamic port (`WireMockTestResource`) |
| khal | A fake `khal` script (`src/test/resources/fakes/khal`) |
| headless Chromium | A fake `chromium` script (`src/test/resources/fakes/chromium`) |
| a browser using the UI | Playwright with headless Chromium (`BrowserTest`). It downloads its browser on first use. |

The HTTP test port is random (`quarkus.http.test-port=0`), so test runs never collide.

### The fake khal and Chromium

`KhalTestResource` runs for every Quarkus test. It creates a temporary directory holding:
- a khal config with five calendars: `Family-and-Kids`, `Kids medical`, `Football`, `Bills` and `Dinners`
- an empty vdir for each calendar
- copies of the fake executables

It points `postmaster.khal-config`, `postmaster.khal-command` and `postmaster.chromium-command` at them. Tests use `FakeTools` to work with them:

- `FakeTools.khalCalls()` and `FakeTools.chromiumCalls()` return each recorded invocation: its arguments, plus the imported `.ics` for `khal import`.
- `FakeTools.eventFiles(calendar)` lists the `.ics` files in a calendar's vdir.
- `FakeTools.failKhal(message)` and `FakeTools.failChromium(message)` make later calls fail with that message on stderr.
- `FakeTools.reset()` clears recorded calls, failure switches and calendar contents.

What the fakes do:
- **khal:**
  - `printcalendars` lists the calendars in the config.
  - `import` copies the file into the chosen calendar's vdir as `<UID>.ics`.
  - Every other command succeeds silently.
- **Chromium:** `--dump-dom <url>` fetches the URL with curl, sending the `--user-agent` value. That way, WireMock stubs answer both the browser and the curl code paths.

### GreenMail and WireMock

Annotate a test class with `@WithTestResource(GreenMailTestResource::class)` or `@WithTestResource(WireMockTestResource::class)`. Then receive the running server in a field:

```kotlin
@field:InjectGreenMail
lateinit var greenMail: GreenMail

@field:InjectWireMock
lateinit var wireMock: WireMockServer
```

- The IMAP and SMTP account is `GreenMailTestResource.USER` / `PASSWORD`.
- The connection settings are in the database (REQ Configuration), so a test points the `imap.*`, `smtp.*` and `llm.url` settings at the servers' ports.

## Test profiles and data

- **Default profile.** The production migrations and seeds only. Tests that change data use `@TestTransaction`, so their changes are rolled back.
- **`KnownDataProfile`.** Adds the known data set in `src/test/resources/db/testdata/V900__known_data.sql`, through Flyway location `db/testdata`. Production migrations use `V1`–`V899`, and `V900`+ is reserved for test data. Rows have distinctive names, so tests look them up by name, never by id. The data set contains:
  - one soft-deleted type
  - four calendars, one of them disabled, plus one soft-deleted calendar
  - a user prompt and a deleted prompt
  - ten rules (classification and action, including disabled and deleted ones), each with conditions and actions
  - nine individual emails covering every status, bills, events, attachments, rule matches, action results and errors
  - 60 "Weekly update" emails, so lists have more than 30 rows and pages of 50
  - two processing runs
- **`SeedDataTest.SeedsOnly`.** A separate application and database for the seed checks, so exact row counts aren't affected by other tests.

Each distinct profile starts its own application. Tests in the same profile share one application and database.

## Layout

| Package | Contents |
|---|---|
| `sh.hutch.postmaster` | startup: command line argument, config file, application boot |
| `sh.hutch.postmaster.db` | schema, naming conventions, portability (CLAR D20), seeds, the known data set |
| `sh.hutch.postmaster.domain` | stored values and the condition compatibility matrix (CLAR Q9, Q10) |
| `sh.hutch.postmaster.entities` | entity rules, such as "never action required for a bill" (CLAR Q13 revision) |
| `sh.hutch.postmaster.util` | ISO 8601 durations |
| `sh.hutch.postmaster.services` | encryption, settings and Message-IDs |
| `sh.hutch.postmaster.mappers` | MapStruct mappers |
| `sh.hutch.postmaster.repositories` | repository queries, soft deletes, UTC timestamps (with the JVM in Melbourne time) |
| `sh.hutch.postmaster.testsupport` | test resources, fakes, HTML and database helpers, the Playwright base class |

## Writing tests

- Test behaviour through HTTP requests and responses where possible (REQ Technical choices). Parse HTML with the Jsoup helpers in `Html.kt` (`getHtml`, `one`, `none`, `testId`).
- Browser tests extend `BrowserTest` and use `visit(path)` or `openPage(colorScheme, width)`.
- Use the routes, form fields and `data-testid`s recorded in `docs/dev/contracts.md`.
- Never weaken a test so that it passes. A failing test means the code or the requirements need to change.
