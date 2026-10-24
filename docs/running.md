# Running Postmaster

## Tools

- **Java:** GraalVM CE for JDK 25. With SDKMAN, `sdk env` in the project directory selects the version in `.sdkmanrc` (`25.4.4.1+1-graalce`). Any JDK 25 works for everything except native builds.
- **Gradle:** the wrapper (`./gradlew`) downloads Gradle 9.8.2.
- **khal** (optional locally), for calendar events: `pipx install khal`. Tests use a fake khal, but `RealKhalTest` also runs against the real one when it's on the `PATH`.
- **Docker**, to build the container image.

## Dev mode: no setup needed

```sh
bin/dev            # or: ./gradlew quarkusDev
```

Then open http://localhost:8080/mail/.

Dev mode (the Quarkus `dev` profile) needs no database, mail server, LLM or configuration of your own:
- **Database:** an in-memory H2 database (MariaDB mode). Flyway creates the schema and the usual seeds.
- **Fake data:** `config/DevData.kt` adds calendars, rules, about 60 emails in every state (with events, attachments, rule matches, action results and errors) and some processing runs. It writes a khal config and the attachment files under `build/dev/`.
- **No processing:** scheduled processing is off. "Run now" and mailbox actions (move, mark read, delete, forward) fail with an error toast, because there's no mail server. Everything else works.
- **Startup configuration:** `config/dev.yml`, which the Gradle `quarkusDev` task passes as the application's argument. The dev settings themselves are in the `%dev` section of `src/main/resources/application.yaml`.

Code and template changes are picked up on the next page load. The data lasts until dev mode stops.

The MCP server is at http://localhost:8080/mail/mcp, and the OpenAPI document at http://localhost:8080/mail/q/openapi (`./gradlew quarkusBuild` also writes it to `docs/openapi.yml`).

## A real instance

Postmaster takes one argument: the path of its YAML startup configuration. Copy `config/postmaster.example.yml`, set the MariaDB connection, the encryption key (`openssl rand -base64 32`) and the khal config path (see [configuration.md](configuration.md)), then:

```sh
bin/build-jvm
java -jar build/quarkus-app/quarkus-run.jar /path/to/postmaster.yml
```

or, as a native executable for this machine:

```sh
bin/build-native
build/postmaster-0.1.0-SNAPSHOT-runner /path/to/postmaster.yml
```

The database must exist; Flyway creates and updates the tables at startup. Then set the Dovecot, SMTP and LLM settings on the Configuration page.

The container image is described in [deployment.md](deployment.md).

## Status

`GET /mail/health` (or `/mail/status`) says, as JSON, whether Postmaster and what it depends on are up. It also gives the version, build time, uptime and what processing is doing:

```json
{
  "status": "DEGRADED",
  "time": "2026-10-25T03:15:02.114Z",
  "application": {
    "name": "postmaster", "version": "v12", "builtAt": "2026-10-25T01:02:03Z",
    "startedAt": "2026-10-25T01:10:00Z", "uptime": "PT2H5M2S", "uptimeSeconds": 7502
  },
  "processing": {
    "inProgress": false, "currentRun": null,
    "lastRun": { "id": 41, "trigger": "schedule", "status": "succeeded", "startedAt": "…", "finishedAt": "…",
                 "emailsFound": 3, "emailsProcessed": 3, "emailsFailed": 0, "error": null },
    "lastSuccessAt": "2026-10-25T03:00:00Z", "scheduled": true, "nextRunAt": "2026-10-25T04:00:00Z",
    "pendingEmails": 1, "failedEmailsLastDay": 0
  },
  "checks": {
    "database": { "status": "UP", "critical": true, "componentType": "datastore", "target": "jdbc:mariadb://mariadb:3306/postmaster", "responseTimeMs": 2, "output": null },
    "dovecot": { "status": "UP", "critical": true, "componentType": "mail", "target": "mail-dovecot:143", "responseTimeMs": 41, "output": null },
    "llm": { "status": "DOWN", "critical": false, "componentType": "http", "target": "http://kodiakbear:9931/v1", "responseTimeMs": 5003, "output": "No answer from http://kodiakbear:9931/v1/models within PT5S" },
    "smtp": { "status": "UP", "critical": false, "componentType": "mail", "target": "mail-opensmtp:25", "responseTimeMs": 12, "output": null }
  }
}
```

| `status` | Meaning | HTTP |
|---|---|---|
| `OK` | Everything is up. | 200 |
| `DEGRADED` | The LLM or SMTP is down. Email waits for the LLM; forwarding fails. | 200 |
| `DOWN` | The database or Dovecot is down: running, but it can't do its job. | 503 |

Each check gets 5 seconds, and they run at the same time. The Docker image's `HEALTHCHECK` calls the endpoint every minute.

The version is a build number (`v1`, `v2`, …) from `version.properties`. Each production build (`./gradlew quarkusBuild` or `bin/build-image`) raises the number for the next build, so commit `version.properties` with each release. Add `-PkeepVersion` to build without raising it.

## Logs

Everything is logged to stdout, in every profile, so Docker collects it. Each line shows the MDC: the processing run, email, phase, rule and action it concerns. For example:

```
2026-10-24 20:37:17,755 WARN  [sh.hut.pos.ser.EmailService] (executor-thread-1) {action=mark_read, email=12, source=ui} Manual action failed: …
```

See [architecture.md](architecture.md#logging) for the keys.

## Tests

`bin/test` (or `./gradlew test`) runs the whole suite on H2, with GreenMail, WireMock and fake khal and Chromium. See [testing.md](testing.md).
