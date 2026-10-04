# OpenBased

OpenBased is a self-hosted media server. This repository contains the server and its REST API (`/api/v1`).
The API is the product's only interface: the web UI, mobile apps and third-party clients all use the
same endpoints and the same OAuth2 tokens.

- Java 21, Spring Boot 3.5, Spring Authorization Server (OAuth2/OIDC)
- H2 (embedded, default) or PostgreSQL
- ffmpeg/ffprobe (optional) for stream probing, remuxing and transcoding

## Running

```sh
mvn package
java -jar target/openbased-0.1.0-SNAPSHOT.jar
```

Open http://localhost:8080/ for the web UI. It is a plain client of the API: it signs in through
`/oauth2/authorize` with PKCE using the `openbased-web` client, so `http://<host>/callback` must be one of
that client's redirect URIs (and `http://<host>/` a post-logout redirect URI) when you serve it from
another address.

On first start an `admin` account is created. Its password is taken from
`openbased.bootstrap.admin-password`, or generated and printed to the log once.

Everything the server writes (database, signing key, uploads, artwork, plugins) goes to
`openbased.data-dir` (default `./data`).

API documentation is generated from the controllers:

- OpenAPI: `GET /api/v1/openapi.json`
- Swagger UI: `GET /api/v1/docs`

### Installing as a Linux service (systemd)

```sh
mvn package
sudo packaging/linux/install.sh
```

The installer needs Java 21+ (and ideally ffmpeg). It creates an `openbased` system user and installs:

| Path | Contents |
| --- | --- |
| `/opt/openbased/openbased.jar` | The server |
| `/etc/openbased/application.yml` | Settings (issuer, port, clients, metadata); overrides the built-in defaults |
| `/etc/openbased/openbased.env` | Secrets and JVM options: `TMDB_API_KEY`, `OPENBASED_ADMIN_PASSWORD`, `JAVA_OPTS` |
| `/var/lib/openbased` | Database, signing key, uploads, artwork, plugins |
| `/etc/systemd/system/openbased.service` | The systemd unit |

Then:

```sh
sudo systemctl status openbased
sudo journalctl -u openbased -f                              # logs
sudo journalctl -u openbased | grep 'generated password'     # first admin password, if none was set
```

The `openbased` user needs read access to your media folders, and write access if you upload into them,
for example `sudo setfacl -R -m u:openbased:rX /srv/media`. When reaching the server under another
address, set `issuer` and the `openbased-web` redirect URIs in `/etc/openbased/application.yml` to it.

To upgrade, build the new version and run `install.sh` again; configuration and data are kept.
`sudo packaging/linux/uninstall.sh` removes the service (add `--purge` to also delete configuration, data
and the user; media files are never touched).

### Configuration

All settings live under `openbased.*` in [`application.yml`](src/main/resources/application.yml).
The important ones:

| Property | Purpose |
| --- | --- |
| `openbased.issuer` | Public base URL; becomes the OAuth2/OIDC issuer. Must match how clients reach the server. |
| `openbased.clients[*]` | OAuth2 clients. Omit `client-secret` for public clients (PKCE is then required). |
| `openbased.clients[*].service-user` | For `client_credentials`: the account whose permissions the client acts with. |
| `openbased.cors-allowed-origins` | Origins of browser clients hosted elsewhere. |
| `openbased.rate-limit.*` | Requests per window per user/client/IP. |
| `openbased.uploads.*` | Chunk size, maximum size, allowed extensions. |
| `openbased.playback.*` | ffmpeg/ffprobe paths, idle timeout for playback sessions. |
| `openbased.metadata.tmdb.api-key` | Enables the built-in TMDB metadata provider (or set `TMDB_API_KEY`). |

To use PostgreSQL, set `spring.datasource.url`, `username` and `password`.

### Tests

```sh
mvn test
```

The integration tests start the server on a random port and drive it over HTTP and WebSocket, including
OAuth2 token issuance, scans, Range streaming, resumable uploads, events, personal access tokens,
library isolation between users and the plugin lifecycle.

## Authentication and authorization

| Flow | Use |
| --- | --- |
| Authorization Code + PKCE | Interactive clients (`/oauth2/authorize`, `/oauth2/token`) |
| Client Credentials | Machine-to-machine; acts as the configured `service-user` |
| Personal access tokens (`ob_pat_…`) | Scripts; created on the web UI's **API tokens** page or with `POST /api/v1/tokens` |

Discovery is at `/.well-known/openid-configuration`, keys at `/oauth2/jwks`, and UserInfo at
`/api/v1/userinfo`.

Every protected request is checked for:

1. a valid, unexpired token (signed access token issued by this server, or an unrevoked PAT);
2. the endpoint's scope on the token;
3. the user's permission of the same name (scopes and permissions share names);
4. access to the resource. Users see a library when it is shared with all users, they are a member, or
   they hold `server.admin`. Media in other libraries is reported as `404`, so its existence is not revealed.

Access tokens only carry the requested scopes the user actually holds. ID tokens are rejected as bearer
tokens. A PAT can only be created with scopes held by both the user and the token used to create it.

The `USER` role grants `openid profile email media.read media.stream library.read history.read history.write`;
`ADMIN` grants every scope. Additional permissions can be granted per user.

### Streaming links

Media URLs never work without authorization. Browsers cannot attach headers to `<video>` elements or
WebSockets, so a short-lived OAuth2 access token may be passed as `?access_token=` **only** on
`GET /api/v1/playback/{sessionId}/stream` and `/api/v1/events`. Personal access tokens are never accepted
in URLs. Playback sessions belong to the user that created them and end on `DELETE`, after the idle
timeout, or on restart.

## Endpoints

| Endpoint | Scope |
| --- | --- |
| `GET /api/v1/userinfo` | `openid` |
| `GET /api/v1/users/me`, `GET /api/v1/users/me/permissions` | `profile` |
| `GET /api/v1/users`, `GET /api/v1/users/{id}` | `users.read` |
| `POST /api/v1/users`, `PATCH /api/v1/users/{id}` | `users.write` |
| `GET /api/v1/libraries`, `GET /api/v1/libraries/{id}` | `library.read` |
| `POST /api/v1/libraries`, `PATCH`/`DELETE /api/v1/libraries/{id}`, `POST /api/v1/libraries/{id}/scan` | `library.write` |
| `GET /api/v1/media`, `GET /api/v1/media/{id}`, `GET /api/v1/search`, `GET /api/v1/artwork/{id}` | `media.read` |
| `GET /api/v1/metadata/search` | `media.read` |
| `POST /api/v1/media/{id}/metadata/refresh` | `library.write` |
| `GET /api/v1/media/{id}/stream` | `media.stream` |
| `POST /api/v1/playback/sessions`, `GET`/`DELETE /api/v1/playback/{id}`, `GET /api/v1/playback/{id}/stream` | `media.stream` |
| `PUT /api/v1/media/{id}/progress` | `history.write` |
| `GET /api/v1/media/{id}/progress`, `GET /api/v1/history`, `GET /api/v1/continue-watching` | `history.read` |
| `POST`/`GET`/`PATCH`/`DELETE /api/v1/uploads…` | `upload` |
| `GET`/`POST`/`DELETE /api/v1/tokens…` | `profile` |
| `GET /api/v1/plugins` | `plugins.read` or `plugins.manage` |
| `POST /api/v1/plugins…`, `DELETE /api/v1/plugins/{id}`, `/api/v1/plugins/{id}/settings` | `plugins.manage` |
| `GET /api/v1/jobs/{id}`, `POST /api/v1/jobs/{id}/cancel` | job owner, or `server.read` / `server.admin` |
| `WS /api/v1/events` | any token; events are filtered per subscriber |

Errors always use the standard body:

```json
{ "error": "MEDIA_NOT_FOUND", "message": "…", "requestId": "01J…", "timestamp": "…" }
```

The `requestId` is also returned in the `X-Request-Id` header. List endpoints take `page` (from 0) and
`pageSize` (1–200) and return `items`, `page`, `pageSize` and `total`.

### Units

- `duration` on media and files: milliseconds. `runtime`: minutes.
- Playback progress `position`/`duration`: seconds. If `completed` is omitted it is set once 95% is reached.

### Playback

`POST /api/v1/playback/sessions` compares the file (probed with ffprobe) with the client's
`capabilities`:

- `DIRECT_PLAY`: container and codecs are supported; the file is served with `Range` support.
- `REMUX`: codecs are supported but the container is not; streams are copied into fragmented MP4 or WebM.
- `TRANSCODE`: re-encoded to H.264/AAC in MP4, or VP9/Opus in WebM.

Remuxed and transcoded streams do not support `Range`; seek with `?start=<seconds>`.

### Uploads

1. `POST /api/v1/uploads` with `filename`, `size`, `libraryId` returns `id` and `chunkSize`.
2. `PATCH /api/v1/uploads/{id}` with `Content-Type: application/octet-stream` and
   `Content-Range: bytes start-end/total`. A chunk may start anywhere up to the bytes already received,
   so a chunk whose response was lost can be re-sent. To resume, read `received` from
   `GET /api/v1/uploads/{id}`.
3. `POST /api/v1/uploads/{id}/complete` moves the file into the library's first folder and processes it in
   the background. `GET /api/v1/uploads/{id}` then reports `COMPLETED` and the `mediaId`.

Abandoned uploads are cancelled after `openbased.uploads.stale-after`.

### Events

`WS /api/v1/events` sends JSON events such as
`{"type":"MEDIA_ADDED","timestamp":"…","data":{"mediaId":"media_…"}}`. Media and scan events go to users
who can see the library, playback and upload events go to the user they concern, and `USER_*` events go to
holders of `users.read`. The connection closes (code 4001) when its token expires.

## Plugins

A plugin is a JAR containing `META-INF/openbased-plugin.properties`:

```properties
id=com.example.plugin
name=Example Plugin
version=1.0.0
main=com.example.ExamplePlugin
```

`main` implements `org.openbased.plugin.api.OpenBasedPlugin`. In `start(PluginContext)` a plugin can
register metadata providers, media processors (run after new media is scanned), event listeners, REST
endpoints under `/api/v1/plugins/{pluginId}/…` (each with an optional required scope), scheduled jobs and
settings.

Install with `POST /api/v1/plugins`, sending the JAR either as multipart field `file` or as an
`application/java-archive` body. Plugins are installed **disabled** and run with the server's privileges,
so only `plugins.manage` holders can install or enable them.

## Known gaps

- OAuth2 authorizations, consents and playback sessions are kept in memory: signed-in users must
  re-authorize after a restart (access tokens stay valid until they expire because the signing key is
  persisted).
- Spring Authorization Server does not issue refresh tokens to public clients, so refresh tokens are only
  available to confidential clients. Public clients re-run the authorization flow; the sign-in session
  makes this silent.
- The token endpoint's `scope` field echoes the requested scopes; the access token itself only carries
  those the user holds.
- Artwork is sent with `Cache-Control: private, max-age=86400` instead of `public` so that shared caches
  never serve images to clients that have not passed the access check.
- Plugins cannot yet register storage or authentication providers.
- The schema is managed with Hibernate `ddl-auto: update`; versioned migrations (Flyway) should replace
  it before the first release.
