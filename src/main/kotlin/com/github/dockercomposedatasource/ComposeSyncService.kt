package com.github.dockercomposedatasource

import com.intellij.database.autoconfig.DataSourceRegistry
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
        val (files, discovered) = ReadAction.compute<Pair<List<String>, List<PostgresService>>, RuntimeException> {
            val composeFiles = ComposeFileScanner.findComposeFiles(project)
            val services = composeFiles.flatMap { file ->
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@flatMap emptyList()
                ComposeParser.parse(text, file.path)
            }
            composeFiles.map { it.path } to services
        }
        val filesScanned = files.size
        val desired = discovered.distinctBy { it.managedId }

        thisLogger().info(
            "Compose scan: ${files.size} compose file(s) $files; " +
                "discovered ${desired.size} postgres service(s) ${desired.map { it.managedId }}",
        )

        val added = mutableListOf<PostgresService>()
        val updated = mutableListOf<PostgresService>()
        val unchanged = mutableListOf<PostgresService>()
        val removed = mutableListOf<String>()

        runOnEdt {
            val manager = LocalDataSourceManager.getInstance(project)
            val all = manager.dataSources
            val managed = all.mapNotNull { ds ->
                ds.getAdditionalProperty(MANAGED_KEY)?.let { id -> id to ds }
            }.toMap()
            val existingUrls = all.mapNotNull { it.url }.toSet()
            val desiredIds = desired.map { it.managedId }.toSet()

            for (svc in desired) {
                val existing = managed[svc.managedId]
                when {
                    existing != null && matches(existing, svc) -> unchanged.add(svc)

                    existing != null -> {
                        // Service was modified: replace in place (keeps it tidy and
                        // avoids needing per-field setters).
                        runCatching {
                            manager.removeDataSource(existing)
                            register(manager, svc)
                        }.onSuccess { updated.add(svc) }
                            .onFailure { thisLogger().warn("Failed to update ${svc.dataSourceName}", it) }
                    }

                    // Not managed by us, but a data source with this URL already
                    // exists (e.g. user-created). Leave it alone, don't duplicate.
                    svc.jdbcUrl in existingUrls -> unchanged.add(svc)

                    else -> runCatching { register(manager, svc) }
                        .onSuccess { added.add(svc) }
                        .onFailure { thisLogger().warn("Failed to register ${svc.dataSourceName}", it) }
                }
            }

            // Remove our data sources whose service no longer exists — but only if the
            // scan actually found compose files, so a premature/empty scan never wipes
            // everything.
            if (filesScanned > 0) {
                for ((id, ds) in managed) {
                    if (id !in desiredIds) {
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

    private fun matches(existing: LocalDataSource, svc: PostgresService): Boolean {
        return existing.url == svc.jdbcUrl && existing.username == svc.user
    }

    private fun register(manager: LocalDataSourceManager, svc: PostgresService) {
        val registry = DataSourceRegistry(project)
        val builder = registry.builder
            .withName(svc.dataSourceName)
            .withGroupName(GROUP_NAME)
            .withDriverClass(postgresDriverClass)
            .withUrl(svc.jdbcUrl)
            .withUser(svc.user)
        if (svc.password != null) builder.withPassword(svc.password)
        builder.commit()

        for (dataSource in registry.newDataSources) {
            // Tag so we can recognize and reconcile it later.
            dataSource.setAdditionalProperty(MANAGED_KEY, svc.managedId)
            // Compose passwords are plaintext in the file already, so persist them
            // into the credential store rather than prompting on first connect.
            if (svc.password != null) {
                dataSource.setPasswordStorage(LocalDataSource.Storage.PERSIST)
            }
            manager.addDataSource(dataSource)
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeAndWait(block)
    }

    companion object {
        /** Additional-property key marking a data source as created/owned by this plugin. */
        const val MANAGED_KEY = "dockerComposeDatasource.managedId"

        /** Group folder the plugin's data sources are placed under in the tool window. */
        const val GROUP_NAME = "Docker Compose"

        fun getInstance(project: Project): ComposeSyncService = project.service()
    }
}
