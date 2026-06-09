package fi.rce.idea.datasources

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.autoconfig.DataSourceRegistry
import com.intellij.database.dataSource.DatabaseCredentialsAuthProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore

/** Outcome of a sync run, used to drive user notifications. */
data class SyncResult(
    val filesScanned: Int,
    val added: List<PostgresService>,
    val updated: List<PostgresService>,
    val removed: List<String>,
    val unchanged: List<PostgresService>,
) {
    val changed: Boolean get() = added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()
}

/**
 * Scans the project for Postgres services declared in compose files and reconciles
 * them with the IDE's data sources:
 *  - new services are registered,
 *  - modified services (port/db/user/password changes) update their data source,
 *  - services removed from compose have their data source deleted.
 *
 * Only data sources this plugin created are ever modified or deleted — they are
 * tagged with [MANAGED_KEY] so user-created data sources are never touched.
 */
@Service(Service.Level.PROJECT)
class ComposeSyncService(private val project: Project) {

    private val postgresDriverClass = "org.postgresql.Driver"

    fun sync(): SyncResult {
        // sync() always runs off the EDT (background task / startup coroutine), so a
        // synchronous non-blocking read action is safe and avoids the deprecated
        // blocking read-action helpers.
        val (files, discovered) = ReadAction.nonBlocking<Pair<List<String>, List<PostgresService>>> {
            val composeFiles = ComposeFileScanner.findComposeFiles(project)
            val services = composeFiles.flatMap { file ->
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@flatMap emptyList()
                ComposeParser.parse(text, file.path)
            }
            composeFiles.map { it.path } to services
        }.executeSynchronously()
        val filesScanned = files.size
        // A data source is identified by its connection URL; the same database
        // referenced by several compose files (e.g. copies under cdk.out) collapses
        // to a single data source.
        val desired = discovered.distinctBy { it.jdbcUrl }

        thisLogger().info(
            "Compose scan: ${files.size} compose file(s); discovered ${desired.size} " +
                "unique postgres connection(s) ${desired.map { it.jdbcUrl }}",
        )

        val added = mutableListOf<PostgresService>()
        val updated = mutableListOf<PostgresService>()
        val unchanged = mutableListOf<PostgresService>()
        val removed = mutableListOf<String>()

        runOnEdt {
            val manager = LocalDataSourceManager.getInstance(project)
            val managed = manager.dataSources.filter { it.getAdditionalProperty(MANAGED_KEY) != null }
            val managedByUrl = managed.groupBy { it.url }
            val desiredUrls = desired.map { it.jdbcUrl }.toSet()

            for (svc in desired) {
                val existing = managedByUrl[svc.jdbcUrl].orEmpty()

                // Clean up any duplicate data sources for this same connection (e.g.
                // left by an earlier version that registered one per compose file).
                existing.drop(1).forEach { dup ->
                    runCatching { manager.removeDataSource(dup) }
                        .onSuccess { removed.add(dup.name) }
                        .onFailure { thisLogger().warn("Failed to remove duplicate ${dup.name}", it) }
                }

                val keep = existing.firstOrNull()
                when {
                    keep == null -> runCatching { register(manager, svc) }
                        .onSuccess { added.add(svc) }
                        .onFailure { thisLogger().warn("Failed to register ${svc.dataSourceName}", it) }

                    needsUpdate(keep, svc) -> runCatching {
                        manager.removeDataSource(keep)
                        register(manager, svc)
                    }.onSuccess { updated.add(svc) }
                        .onFailure { thisLogger().warn("Failed to update ${svc.dataSourceName}", it) }

                    else -> unchanged.add(svc)
                }
            }

            // Remove our data sources whose connection is no longer declared — but only
            // if the scan actually found compose files, so a premature/empty scan never
            // wipes everything.
            if (filesScanned > 0) {
                for (ds in managed) {
                    if (ds.url !in desiredUrls) {
                        runCatching { manager.removeDataSource(ds) }
                            .onSuccess { removed.add(ds.name) }
                            .onFailure { thisLogger().warn("Failed to remove ${ds.name}", it) }
                    }
                }
            }
        }

        thisLogger().info(
            "Compose sync done: added ${added.size}, updated ${updated.size}, " +
                "removed ${removed.size}, unchanged ${unchanged.size}",
        )
        return SyncResult(filesScanned, added, updated, removed, unchanged)
    }

    /** A kept data source needs recreating if its user changed or its stored password is missing/stale. */
    private fun needsUpdate(existing: LocalDataSource, svc: PostgresService): Boolean {
        if (existing.username != svc.user) return true
        // Heal data sources whose password was never persisted (older versions).
        if (svc.password != null && storedPassword(existing) != svc.password) return true
        return false
    }

    private fun storedPassword(ds: LocalDataSource): String? =
        runCatching { DatabaseCredentialsAuthProvider.getCredentials(ds)?.getPasswordAsString() }.getOrNull()

    private fun register(manager: LocalDataSourceManager, svc: PostgresService) {
        val credentials = DatabaseCredentials.getInstance()
        val registry = DataSourceRegistry(project, credentials)
        registry.builder
            .withName(svc.dataSourceName)
            .withGroupName(GROUP_NAME)
            .withDriverClass(postgresDriverClass)
            .withUrl(svc.jdbcUrl)
            .withUser(svc.user)
            .commit()

        for (dataSource in registry.dataSources) {
            // Tag so we can recognize and reconcile it later.
            dataSource.setAdditionalProperty(MANAGED_KEY, MANAGED_VALUE)
            // Compose passwords are plaintext in the file already, so persist them into
            // the credential store rather than prompting on first connect.
            if (svc.password != null) {
                dataSource.setPasswordStorage(LocalDataSource.Storage.PERSIST)
                credentials.storePassword(dataSource, OneTimeString(svc.password))
            }
            manager.addDataSource(dataSource)
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeAndWait(block)
    }

    companion object {
        // Additional-property key marking a data source as created/owned by this plugin.
        // Kept stable (the legacy name) so data sources tagged by earlier versions are
        // still recognized and reconciled.
        const val MANAGED_KEY = "dockerComposeDatasource.managedId"
        private const val MANAGED_VALUE = "true"

        /** Group folder the plugin's data sources are placed under in the tool window. */
        const val GROUP_NAME = "Docker Compose"

        fun getInstance(project: Project): ComposeSyncService = project.service()
    }
}
