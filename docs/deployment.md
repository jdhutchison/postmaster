# Deployment

Postmaster runs as a container on tigerbear, on the `services` and `mariadb` Docker networks (CLARIFICATIONS.md S1). The Ansible play that sets this up (PLAN.md Phase 9) is on hold; this page covers the image and how to run it.

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
| the calendars directory | the vdirs that `khal.ini`'s `path`s name, mounted at those same paths (read-write, for the `calendars` group) |
| `/data/attachments` | downloaded attachments and bills; set the `attachments.path` setting to it |

For example:

```sh
docker run -d --name postmaster \
    --network services --network mariadb \
    -v /drive/services/postmaster/postmaster.yml:/config/postmaster.yml:ro \
    -v /drive/sys/config/khal.ini:/config/khal/khal.ini:ro \
    -v /drive/sys/data/calendars:/drive/sys/data/calendars \
    -v /drive/services/postmaster/attachments:/data/attachments \
    postmaster:native
```

- The UI is at `http://postmaster:8080/mail/`. nginx serves it at `https://hutch.sh/mail` with basic auth.
- The MCP server is at `http://postmaster:8080/mail/mcp`. Keep it off nginx ([mcp.md](mcp.md)).
- Logs go to stdout, so `docker logs postmaster` shows them.

The database and user must exist; Flyway creates the tables at startup. Then fill in the Dovecot, SMTP and LLM settings on the Configuration page ([configuration.md](configuration.md)).
