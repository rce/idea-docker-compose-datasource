# Docker Compose Postgres Datasources

An IntelliJ IDEA **Ultimate** plugin that scans `docker-compose` / `compose` YAML
files in your project, finds PostgreSQL services, and registers them as database
data sources in the IDE.

## What it does

- Detects services whose `image` references postgres/postgis
  (e.g. `postgres:16`, `postgis/postgis`, `bitnami/postgresql`).
- Reads `POSTGRES_USER`, `POSTGRES_DB`, `POSTGRES_PASSWORD` (map and list
  `environment` forms), and the host port published for container port `5432`
  (short and long `ports` syntax).
- Registers each as a PostgreSQL data source pointing at `localhost:<hostPort>`,
  persisting the compose password into the credential store.
- Idempotent: existing data sources (matched by JDBC URL) are skipped.

## Triggers

- **Manual:** `Tools | Sync Docker Compose Datasources`.
- **Automatic:** scans on project open (silent unless it adds something). Toggle
  via the per-project setting `autoSyncOnProjectOpen` in
  `dockerComposeDatasources.xml`.

> Passwords found in compose files are persisted into the IDE credential store
> (they are plaintext in the compose file anyway). Host is assumed `localhost`.

## Requirements

- IntelliJ IDEA **Ultimate** 2024.3+ (the Database Tools & SQL plugin is required
  and ships only with Ultimate).
- JDK 21.

## Build & run

```sh
./gradlew test          # run the parser unit tests
./gradlew buildPlugin   # produce build/distributions/*.zip
./gradlew runIde        # launch a sandbox IDE with the plugin installed
```

When running the sandbox IDE, open a project containing a compose file (the
included `samples/docker-compose.yml` is a good test) and run the Tools action.

## Project layout

| Path | Purpose |
| --- | --- |
| `ComposeFileScanner` | Finds compose files, skipping excluded/ignored locations. |
| `ComposeParser` | Parses YAML → `PostgresService` (pure, unit-tested). |
| `ComposeSyncService` | Reconciles data sources via the Database API. |
| `SyncComposeDatasourcesAction` | Tools-menu manual trigger. |
| `ComposeStartupActivity` | Auto-scan on project open. |
| `ComposeDatasourceSettings` | Per-project toggle for the auto-scan. |

## Publishing to the JetBrains Marketplace

The plugin is signed and published with the standard Gradle tasks. Provide these
secrets via the environment (or `~/.gradle/gradle.properties`) — never commit them:

| Variable | Purpose |
| --- | --- |
| `PUBLISH_TOKEN` | Marketplace API token (Marketplace → My Tokens). |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Plugin [signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) key. |

```sh
./gradlew verifyPlugin   # compatibility check (same as Marketplace runs)
./gradlew signPlugin     # produces build/distributions/*-signed.zip
./gradlew publishPlugin  # signs + uploads to the "default" channel
```

The **first** version is uploaded manually via the Marketplace web UI (to set
category, license and screenshots) and goes through a one-time review. After that,
`publishPlugin` — or the `.github/workflows/release.yml` workflow on a published
GitHub release — handles updates. Bump `version` and add a `## [x.y.z]` section to
`CHANGELOG.md` for each release; the latest section becomes the listing's change notes.
