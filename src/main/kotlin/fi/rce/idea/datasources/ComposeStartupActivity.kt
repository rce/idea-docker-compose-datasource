package fi.rce.idea.datasources

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Automatic trigger: reconciles Postgres data sources shortly after a project
 * opens, unless disabled in settings. Stays silent unless something changed.
 */
class ComposeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!ComposeDatasourceSettings.getInstance(project).autoSyncOnProjectOpen) return

        try {
            val result = ComposeSyncService.getInstance(project).sync()
            ComposeDatasourceNotifier.notify(project, result, silentWhenNothingNew = true)
        } catch (t: Throwable) {
            thisLogger().warn("Docker Compose datasource auto-sync failed for ${project.name}", t)
        }
    }
}
