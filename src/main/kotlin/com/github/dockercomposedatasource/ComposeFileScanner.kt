package com.github.dockercomposedatasource

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.changes.VcsIgnoreManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Locates docker-compose / compose YAML files within the project's content roots,
 * skipping build-output, dependency, IDE-excluded and VCS-ignored locations so that
 * copies (e.g. under `cdk.out`, `node_modules`, `build`) are not picked up.
 */
object ComposeFileScanner {

    private val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", "target", "out", ".gradle", ".idea",
        "dist", "vendor", "cdk.out",
    )

    private val COMPOSE_PREFIXES = listOf("docker-compose", "compose")
    private val YAML_EXTENSIONS = listOf(".yml", ".yaml")

    fun findComposeFiles(project: Project): List<VirtualFile> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val ignoreManager = runCatching { VcsIgnoreManager.getInstance(project) }.getOrNull()

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
            collect(root, result, seen, fileIndex, ignoreManager)
        }
        return result
    }

    private fun collect(
        file: VirtualFile,
        sink: MutableList<VirtualFile>,
        seen: MutableSet<String>,
        fileIndex: ProjectFileIndex,
        ignoreManager: VcsIgnoreManager?,
    ) {
        if (!file.isValid) return
        if (!seen.add(file.path)) return
        if (isSkipped(file, fileIndex, ignoreManager)) return

        if (file.isDirectory) {
            for (child in file.children) {
                collect(child, sink, seen, fileIndex, ignoreManager)
            }
        } else if (isComposeFile(file.name)) {
            sink.add(file)
        }
    }

    /** Skips well-known output dirs plus anything the IDE excludes or VCS ignores. */
    private fun isSkipped(
        file: VirtualFile,
        fileIndex: ProjectFileIndex,
        ignoreManager: VcsIgnoreManager?,
    ): Boolean {
        if (file.isDirectory && file.name in SKIP_DIRS) return true
        if (runCatching { fileIndex.isExcluded(file) }.getOrDefault(false)) return true
        if (ignoreManager != null &&
            runCatching { ignoreManager.isPotentiallyIgnoredFile(file) }.getOrDefault(false)
        ) {
            return true
        }
        return false
    }

    private fun isComposeFile(name: String): Boolean {
        val lower = name.lowercase()
        if (YAML_EXTENSIONS.none { lower.endsWith(it) }) return false
        return COMPOSE_PREFIXES.any { lower == "$it.yml" || lower == "$it.yaml" || lower.startsWith("$it.") }
    }
}
