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
| `ComposeFileScanner` | Finds compose files in content roots. |
| `ComposeParser` | Parses YAML → `PostgresService` (pure, unit-tested). |
| `ComposeSyncService` | Dedups and registers data sources via the Database API. |
| `SyncComposeDatasourcesAction` | Tools-menu manual trigger. |
| `ComposeStartupActivity` | Auto-scan on project open. |
| `ComposeDatasourceSettings` | Per-project toggle for the auto-scan. |
