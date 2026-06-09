package com.github.dockercomposedatasource

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object ComposeDatasourceNotifier {

    const val GROUP_ID = "Docker Compose Datasources"

    fun notify(project: Project, result: SyncResult, silentWhenNothingNew: Boolean) {
        if (!result.changed && silentWhenNothingNew) return

        val parts = buildList {
            if (result.added.isNotEmpty()) add("added ${result.added.size}")
            if (result.updated.isNotEmpty()) add("updated ${result.updated.size}")
            if (result.removed.isNotEmpty()) add("removed ${result.removed.size}")
        }
        val title = when {
            parts.isNotEmpty() -> "Postgres data sources: ${parts.joinToString(", ")}"
            result.filesScanned == 0 -> "No docker-compose files found"
            else -> "Postgres data sources are up to date"
        }
        val content = buildString {
            fun section(label: String, names: List<String>) {
                if (names.isEmpty()) return
                if (isNotEmpty()) append("<br/>")
                append("$label: ").append(names.joinToString(", "))
            }
            section("Added", result.added.map { it.dataSourceName })
            section("Updated", result.updated.map { it.dataSourceName })
            section("Removed", result.removed)
        }

        // Prefer the registered group (honors the user's notification settings); fall
        // back to a plain notification if the group hasn't resolved for any reason.
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        val notification = group?.createNotification(title, content, NotificationType.INFORMATION)
            ?: Notification(GROUP_ID, title, content, NotificationType.INFORMATION)
        notification.notify(project)
    }
}
