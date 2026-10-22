# Discussion

This file records communication between us after the clarification round. The clarifying questions and answers themselves are in CLARIFICATIONS.md.

---

## 2026-10-22

**You:** Record any changes made to kodiakbear or tigerbear in changes.md.

**Claude:** I created `changes.md` with the tigerbear changes the answers make necessary (T1–T8). The main ones are the new service and the mbsync change to sync Trash (Q6). Each entry moves from Planned to Committed (in the Ansible repo) to Applied (deployed, with the date). No kodiakbear changes are planned. I'll ask before running anything against either host.

**Claude:** The plan is in PLAN.md. Before Phase 0 starts, note:
- There is no JDK or Gradle on this machine. Task 0.2 installs GraalVM and Gradle under your home directory with SDKMAN.
- You asked me to remove the stray 16 digit number at REQUIREMENTS.md line 195 (Q20), and it's gone. If it was a real card number, treat it as exposed.

---

**You:** I accept the plan. Please begin. Do not make any mistakes.

**Claude:** Starting Phase 0. I'll check each task with tests or by running it before marking it done, and anything that doesn't work will be recorded here.

---

**You:** Why is Maven required when Gradle is to be used?

**Claude:** It isn't, and the build is Gradle only. I was querying **Maven Central**, the public library repository. Gradle downloads dependencies from it too (`mavenCentral()` in the build file), and the `.pom` files there are dependency metadata that Gradle reads as well. I was checking current versions so the Gradle build pins versions that exist and work together:
- Quarkus platform 3.40.1.1, which manages Kotlin 2.4.10
- MCP server 2.0.1, part of the Quarkus platform
- GraalVM CE for JDK 25
- Gradle 9.8.2

---

**You:** I realise I made a mistake before. The action_required attribute of an email is intended to be set when the text of an email implies I need to do something. This excludes emails identified as being of the type "bill". Prompts may need updating or added to ensure LLMs can identify if action is needed in response to an email. Update your plans appropriately and add this to the clarifications document.

**Claude:** Done.
- **CLARIFICATIONS.md:**
  - Q13 now has a revised answer.
  - New revision R1 gives the updated Summarise prompt, which returns `action_required` and an `action` description.
  - New default D19 covers fixes the seeded prompts need anyway. The Summarise prompt had no placeholder for the email itself, and its JSON example was invalid.
- **PLAN.md:**
  - The `set_action_needed` action is removed.
  - Seeding includes the revised prompt.
  - The pipeline and MCP enforce "never for Bill".

---

**You:** Also I want one agent to build and another to write the tests, working in parallel. They may communicate and both can ask clarifications but both must comply with the requirements.

**Claude:** Set up as described under "Working arrangement" in PLAN.md:
- The builder owns the production code, build files, Docker, `bin/`, docs and Ansible.
- The tester owns `src/test`, writing tests from the requirements and clarifications rather than from the builder's code.
- The two agents coordinate through `docs/dev/contracts.md` and direct messages.
- They build into separate directories and use random test ports.
- Each commits only its own paths.
- Clarification questions come to me, I ask you, and the answers go in CLARIFICATIONS.md.
- Requirements win any disagreement, and neither agent may weaken the other's work to get a pass.
- Both stop at the end of each phase so I can run the full suite and update PLAN.md.

---

**Builder agent (relayed by Claude):** Docker can't be used on this machine without sudo, because user `hutch` isn't in the `docker` group. This affects two things:
- The temporary MariaDB check of the migrations. The builder is instead running MariaDB from the official binary tarball as an unprivileged user in the session scratchpad, with no root and no system changes.
- The local image build check in task 8.2. This is still open.

**Claude:** I confirmed it: `docker ps` gets permission denied, and `/var/run/docker.sock` is `root:docker 660`. **Open question for you, needed by Phase 8:** either add `hutch` to the `docker` group (`sudo usermod -aG docker hutch`, then log out and back in), or run the local image build yourself when we get there. The real arm64 build happens on tigerbear either way.
