# Changelog

All notable changes to this plugin are documented here. The entries under the
most recent version are shown as the change notes on the JetBrains Marketplace.

## [0.2.0]

- Persist compose passwords into the IDE credential store so connections work without a manual password prompt, and heal data sources that were missing one.
- Identify data sources by connection URL so the same database referenced by several compose files (e.g. copies under cdk.out) yields a single data source, and clean up duplicates from earlier versions.
- Skip IDE-excluded and VCS-ignored locations when scanning.

## [0.1.0]

- Initial release: scan docker-compose files for PostgreSQL services and register them as data sources, manually and automatically on project open.
