# Deployment

Postmaster runs as a container on tigerbear, on the `services` and `mariadb` Docker networks (CLARIFICATIONS.md S1). This page covers the image, how to run it, and the Ansible play that deploys it.

## Deploying to tigerbear

`plays/other/postmaster.yml` in `/data/code/ansible` does everything: the `postmaster` user (UID 925, in `calendars`), the database, `/drive/services/postmaster`, the image, the systemd unit, the nginx include, the MCP server in the Hermes `postmaster` profile, and the mbsync Trash change. It builds the committed HEAD of this repository, so commit first. Every step is safe to re-run; the database password and encryption key are generated once and kept in `/drive/services/postmaster/postmaster.yml`.

```sh
cd /data/code/ansible
ansible-playbook -i tigerbear, -e @group_vars/all.yaml plays/other/postmaster.yml
```

The native build takes about half an hour on tigerbear. The play ends by showing the status (`/mail/health`).

## The image

`Dockerfile` has two targets, built with `bin/build-image [native|jvm]`:

| Target | Contents | Build needs |
|---|---|---|
| `native` (default) | A GraalVM native executable | About 6 GB of memory for the builder. It compiles for the architecture Docker builds for, so the arm64 production image is built on tigerbear (Q2). |
| `jvm` | The JVM build on a Temurin 25 JRE | Much less memory and time; good for trying the image out |

Both run on Debian (trixie) slim with khal, curl and Chromium, as the non-root user `postmaster` in the `calendars` group. Set `POSTMASTER_UID` and `CALENDARS_GID` when building so they match the host's owner of the attachments and its `calendars` group:

```sh
POSTMASTER_UID=$(id -u postmaster) CALENDARS_GID=$(getent group calendars | cut -d: -f3) bin/build-image
```

## Running it

The image's argument is the startup file, `/config/postmaster.yml` by default.

| Mount | Holds |
|---|---|
| `/config/postmaster.yml` | the startup configuration (read only) |
| `/config/khal` | the khal configuration; set `postmaster.khal-config: /config/khal/khal.ini` |
| the calendars directory | the vdirs that `khal.ini`'s `path`s name, mounted at those same paths (read-write, for the `calendars` group). tigerbear's `khal.ini` uses `~/.calendars/...`, so `/drive/sys/data/calendars` goes at `/home/postmaster/.calendars`. Each calendar needs its own `[[name]]` section: `type = discover` isn't supported |
| `/data/attachments` | downloaded attachments and bills; set the `attachments.path` setting to it |
| `/data/todo` | the markdown to-do list (`todo.path`) |

For example:

```sh
docker run -d --name postmaster --stop-timeout 30 \
    --network services --network mariadb \
    -v /drive/services/postmaster/postmaster.yml:/config/postmaster.yml:ro \
    -v /drive/sys/config/khal.ini:/config/khal/khal.ini:ro \
    -v /drive/sys/data/calendars:/drive/sys/data/calendars \
    -v /drive/services/postmaster/attachments:/data/attachments \
    -v /drive/services/postmaster/todo:/data/todo \
    postmaster:native
```

`--stop-timeout 30` (`stop_grace_period: 30s` in a compose file) gives Postmaster time to finish the step it's in when it's stopped; Docker's default is 10 seconds. Stopping it at any point is safe: the next run carries on from the last step saved (CLARIFICATIONS.md R6).

- The UI is at `http://postmaster:8080/mail/`. nginx serves it at `https://hutch.sh/mail` with basic auth.
- The MCP server is at `http://postmaster:8080/mail/mcp`. Keep it off nginx ([mcp.md](mcp.md)).
- Logs go to stdout, so `docker logs postmaster` shows them.

The database and user must exist; Flyway creates the tables at startup. Then fill in the Dovecot, SMTP and LLM settings on the Configuration page ([configuration.md](configuration.md)).
