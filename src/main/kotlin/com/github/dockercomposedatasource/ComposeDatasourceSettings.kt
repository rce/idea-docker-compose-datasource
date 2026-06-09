package com.github.dockercomposedatasource

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-project settings for the plugin.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ComposeDatasourceSettings",
    storages = [Storage("dockerComposeDatasources.xml")],
)
class ComposeDatasourceSettings : PersistentStateComponent<ComposeDatasourceSettings.State> {

    data class State(
        var autoSyncOnProjectOpen: Boolean = true,
    )

    private var state = State()

    var autoSyncOnProjectOpen: Boolean
        get() = state.autoSyncOnProjectOpen
        set(value) {
            state.autoSyncOnProjectOpen = value
        }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): ComposeDatasourceSettings = project.service()
    }
}
