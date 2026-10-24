# Staged tests (not compiled)

Tests the tester agent wrote before the code they test was committed. This directory isn't part of any Gradle source set.

Nothing is staged at the moment. The Phase 5 tests moved into `src/test/kotlin/sh/hutch/postmaster/processing/` on 2026-10-24. Two of them needed changes there:
- the missing `rows` import
- in `mark_read and mark_unread…`, the second rule was renamed from `unreader` to `unseen`. A subject containing `unreader` also matched the older `reader` rule, which then correctly won the read-state conflict (Q12).

Phase 5 tests still to write:
- the scheduler: a frequency change takes effect within a minute, with no restart
- runs left unfinished are marked failed after a restart
