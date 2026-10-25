# Changes to kodiakbear and tigerbear

This file logs every change Postmaster work makes, or will make, to either host.

Status values:
- **Planned**: agreed but not yet made in the Ansible repo.
- **Committed**: made in the Ansible repo but not yet deployed.
- **Applied**: deployed to the host, with the date.

## tigerbear

| # | Change | Where (in `/data/code/ansible`) | Status |
|---|---|---|---|
| T1 | Add a `postmaster` service user (UID/GID 925 in `service_users`), member of the `calendars` group | `group_vars/all.yaml`, `plays/other/postmaster.yml` | Applied 2026-10-25 |
| T2 | Create a `postmaster` database and user in the existing MariaDB. The play's own idempotent tasks are used instead of `tasks/create-mysql-db.yml`, which drops the database on every run | `plays/other/postmaster.yml` | Applied 2026-10-25 |
| T3 | Create `/drive/services/postmaster` (compose file, `postmaster.yml` with a generated database password and encryption key) with `attachments/` and `todo/` | `config/postmaster/`, play | Applied 2026-10-25 |
| T4 | Build the `postmaster` Docker image (native arm64) on tigerbear from the Postmaster repo's committed HEAD | play | Applied 2026-10-25 |
| T5 | Install and enable the `postmaster.service` systemd unit; the container joins the `services` and `mariadb` Docker networks, and mounts `/drive/sys/config/khal.ini` (read only) and `/drive/sys/data/calendars` | `config/postmaster/postmaster.service`, `docker-compose.yml` | Applied 2026-10-25 |
| T6 | Add an nginx include for `https://hutch.sh/mail` (with `/mail/mcp` blocked) using basic auth from `/var/www/.htpasswd` for every request except those from private addresses (the Docker networks); Tailscale and the internet need the password. Add it to the `hutch.sh` site, and restart nginx. nginx's `/var/www` is `/drive/services/nginx/www`, so the play copies the host's `/var/www/.htpasswd` there | `config/postmaster/nginx-include.conf`, play | Applied 2026-10-25 |
| T7 | Register Postmaster's MCP server (`http://postmaster:8080/mail/mcp`) under `mcp_servers` in the Hermes `postmaster` profile `config.yaml` and restart Hermes; the email-triage job is left alone | `plays/other/postmaster.yml` | Applied 2026-10-25 |
| T8 | Sync Trash between Dovecot and Proton by removing `!"Trash"` from the mbsync `Pattern`, then restart the `mail-mbsync` container (it bind-mounts the file) | `config/mail/config/mbsyncrc`, `plays/other/postmaster.yml` | Applied 2026-10-25 |
| T9 | Mount the knowledgebase (`/drive/sys/data/knowledgebase`) at `/data/knowledgebase` in the container, which joins the host's `sync` group (`group_add`) to write to it; the `postmaster` user is added to `sync` | `config/postmaster/docker-compose.yml.j2`, play | Applied 2026-10-25 |
| T10 | Move the hand-started `dvtest` Dovecot 2.4 trial into the mail compose project as `dovecot-test` (container `mail-dovecot-test`, on the `mail` network), with its config in `/drive/services/mail/config/dvtest` and mailboxes in `/drive/system/data/mail/dvtest` instead of `/tmp/dvtest` (the old copy is left there). Started with `docker compose up -d --no-deps`, so the rest of the mail stack wasn't restarted; the previous compose file is `docker-compose.yml.bak-20261025` | `config/mail/docker-compose.yml`, `config/mail/config/dvtest/`, `plays/sync/mail.yml` | Applied 2026-10-25 |

Found while deploying (not caused by Postmaster): Proton Bridge rejected every login with "no such user" from 2026-10-11, so mbsync didn't sync Proton and Dovecot. The account was logged in again on 2026-10-25 (04:04 UTC), but at 04:11 Bridge's IMAP server stopped with "consecutive exists must be non-decreasing, but had 6266 and new 6265" during an mbsync run. The container stays up (its health check only tests the port forwarder), so mbsync gets an EOF every minute.

## kodiakbear

No changes. Postmaster reaches the existing llama.cpp server (`http://kodiakbear:9931/v1`) over Tailscale from its container; its status check lists the models (2026-10-25).
