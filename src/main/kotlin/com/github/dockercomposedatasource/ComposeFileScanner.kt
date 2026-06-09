package com.github.dockercomposedatasource

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Locates docker-compose / compose YAML files within the project's content roots.
 */
object ComposeFileScanner {

    private val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", "target", "out", ".gradle", ".idea", "dist", "vendor",
    )

    private val COMPOSE_PREFIXES = listOf("docker-compose", "compose")
    private val YAML_EXTENSIONS = listOf(".yml", ".yaml")

    fun findComposeFiles(project: Project): List<VirtualFile> {
        // Content roots aren't populated until module import finishes (e.g. Gradle
        // sync), which can be after a project-open scan runs. The project base dir is
        // available immediately, so include it and de-duplicate.
        val roots = buildList {
            project.guessProjectDir()?.let { add(it) }
            addAll(ProjectRootManager.getInstance(project).contentRoots)
        }

        val result = mutableListOf<VirtualFile>()
        val seen = HashSet<String>()
        for (root in roots) {
            collect(root, result, seen)
        }
        return result
    }

    private fun collect(file: VirtualFile, sink: MutableList<VirtualFile>, seen: MutableSet<String>) {
        if (!file.isValid) return
        if (!seen.add(file.path)) return
        if (file.isDirectory) {
            if (file.name in SKIP_DIRS) return
            for (child in file.children) {
                collect(child, sink, seen)
            }
        } else if (isComposeFile(file.name)) {
            sink.add(file)
        }
    }

    private fun isComposeFile(name: String): Boolean {
        val lower = name.lowercase()
        if (YAML_EXTENSIONS.none { lower.endsWith(it) }) return false
        return COMPOSE_PREFIXES.any { lower == "$it.yml" || lower == "$it.yaml" || lower.startsWith("$it.") }
    }
}
