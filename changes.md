# Changes to kodiakbear and tigerbear

This file logs every change Postmaster work makes, or will make, to either host.

Status values:
- **Planned**: agreed but not yet made in the Ansible repo.
- **Committed**: made in the Ansible repo but not yet deployed.
- **Applied**: deployed to the host, with the date.

## tigerbear

| # | Change | Where (in `/data/code/ansible`) | Status |
|---|---|---|---|
| T1 | Add a `postmaster` service user (UID/GID in `service_users`), member of the `calendars` group | `group_vars/all.yaml`, `plays/other/postmaster.yml` | Planned |
| T2 | Create a `postmaster` database and user in the existing MariaDB | `tasks/create-mysql-db.yml` via the play | Planned |
| T3 | Create `/drive/services/postmaster` (compose file, app config) and an attachments directory | `config/postmaster/`, play | Planned |
| T4 | Build the `postmaster` Docker image (native arm64) on tigerbear | play | Planned |
| T5 | Install and enable the `postmaster.service` systemd unit; the container joins the `services` and `mariadb` Docker networks | `config/postmaster/postmaster.service` | Planned |
| T6 | Add an nginx include for `https://hutch.sh/mail` using basic auth from `/var/www/.htpasswd`, add it to the `hutch.sh` site, and restart nginx | `config/postmaster/nginx-include.conf`, play | Planned |
| T7 | Register Postmaster's MCP server in the Hermes `postmaster` profile `config.yaml` and restart Hermes; the email-triage job is left alone | `plays/other/hermes.yml` or the postmaster play | Planned |
| T8 | Sync Trash between Dovecot and Proton by removing `!"Trash"` from the mbsync `Pattern`, then restart the mail stack | `config/mail/config/mbsyncrc` | Planned |

## kodiakbear

No changes are planned. Postmaster calls the existing llama.cpp server (`:9931`) over Tailscale. If its firewall turns out not to admit tigerbear, the fix will be logged here first.
