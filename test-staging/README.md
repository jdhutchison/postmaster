# Staged tests (not compiled)

These are tests the tester agent wrote but hasn't compiled or run, because the code they test wasn't committed when testing was paused (2026-10-24). This directory isn't part of any Gradle source set.

## Phase 5 (`phase5/`)

Tests for the rule engine, processing flow, action handlers and `process_bill`, plus the shared pipeline test setup.

To resume, once the Phase 5 code is committed:
1. Add `postmaster.scheduler-enabled: false` to `src/test/resources/application.yaml`. An unknown `postmaster.` key fails startup, so this waits for the committed code.
2. Copy `phase5/sh/...` into `src/test/kotlin/sh/...` and compile. The names follow contract commit 974c945; check them against the current `docs/dev/contracts.md`.
3. Fill the gaps:
   - a scheduler test: a frequency change takes effect within a minute, with no restart
   - an end-to-end pipeline test
   - a check that runs left unfinished are marked failed after a restart
4. Run the full suite on the latest commit, report failures, set the 5.x `tests:` markers to done, and commit.
