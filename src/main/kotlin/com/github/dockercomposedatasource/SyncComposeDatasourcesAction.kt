package com.github.dockercomposedatasource

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Manual trigger: Tools | Sync Docker Compose Datasources.
 */
class SyncComposeDatasourcesAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runSync(project)
    }

    private fun runSync(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Syncing docker-compose data sources", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = ComposeSyncService.getInstance(project).sync()
                ComposeDatasourceNotifier.notify(project, result, silentWhenNothingNew = false)
            }
        })
    }
}
