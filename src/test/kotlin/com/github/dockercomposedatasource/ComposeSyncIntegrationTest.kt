package com.github.dockercomposedatasource

import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies the end-to-end sync/reconcile logic against a real (test) project and
 * the bundled Database plugin: a sync registers a Postgres data source, repeated
 * syncs are idempotent, and removing the service deletes its data source. Only
 * data sources tagged by this plugin are touched.
 */
class ComposeSyncIntegrationTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            // The light project is shared across test methods; clear data sources so
            // each test starts from a clean slate.
            val manager = LocalDataSourceManager.getInstance(project)
            manager.dataSources.toList().forEach { manager.removeDataSource(it) }
        } finally {
            super.tearDown()
        }
    }

    private val composeYaml = """
        services:
          db:
            image: postgres:16
            environment:
              POSTGRES_USER: meow
              POSTGRES_PASSWORD: nyaa
              POSTGRES_DB: catdb
            ports:
              - "5433:5432"
    """.trimIndent()

    fun `test startup activity and notification group extensions are registered`() {
        val ep = com.intellij.openapi.extensions.ExtensionPointName
            .create<Any>("com.intellij.postStartupActivity")
        assertTrue(
            "ComposeStartupActivity must be registered as a postStartupActivity (auto-scan trigger)",
            ep.extensionList.any { it::class.java.name == ComposeStartupActivity::class.java.name },
        )
        assertNotNull(
            "Notification group must be registered from plugin.xml",
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(ComposeDatasourceNotifier.GROUP_ID),
        )
    }

    fun `test sync registers a postgres data source with marker`() {
        myFixture.addFileToProject("docker-compose.yml", composeYaml)

        val result = ComposeSyncService.getInstance(project).sync()
        assertEquals(1, result.added.size)

        val ds = managedDataSources().single()
        assertEquals("jdbc:postgresql://localhost:5433/catdb", ds.url)
        assertEquals("meow", ds.username)
    }

    fun `test compose password is persisted into the credential store`() {
        myFixture.addFileToProject("docker-compose.yml", composeYaml)

        ComposeSyncService.getInstance(project).sync()

        val ds = managedDataSources().single()
        val credentials = com.intellij.database.dataSource.DatabaseCredentialsAuthProvider.getCredentials(ds)
        assertEquals("nyaa", credentials?.getPasswordAsString())
    }

    fun `test same connection in multiple compose files yields one data source`() {
        // Simulates copies (e.g. under cdk.out) defining the same database.
        myFixture.addFileToProject("docker-compose.yml", composeYaml)
        myFixture.addFileToProject("sub/dir/docker-compose.yml", composeYaml)

        val result = ComposeSyncService.getInstance(project).sync()
        assertEquals(1, result.added.size)
        assertEquals(1, managedDataSources().size)
    }

    fun `test repeated sync is idempotent`() {
        myFixture.addFileToProject("docker-compose.yml", composeYaml)
        val sync = ComposeSyncService.getInstance(project)

        assertEquals(1, sync.sync().added.size)

        val second = sync.sync()
        assertEquals(0, second.added.size)
        assertEquals(1, second.unchanged.size)
        assertEquals(1, managedDataSources().size)
    }

    fun `test removing the service deletes its data source`() {
        val vfile = myFixture.addFileToProject("docker-compose.yml", composeYaml).virtualFile
        val sync = ComposeSyncService.getInstance(project)

        assertEquals(1, sync.sync().added.size)

        // Add a second, unrelated compose file so a scan still finds files after we
        // delete the first (guards the "don't wipe everything on empty scan" rule).
        myFixture.addFileToProject("other/compose.yml", "services: {}\n")
        runWriteAction { vfile.delete(this) }

        val result = sync.sync()
        assertEquals(1, result.removed.size)
        assertTrue(managedDataSources().isEmpty())
    }

    private fun managedDataSources() =
        LocalDataSourceManager.getInstance(project).dataSources
            .filter { it.getAdditionalProperty(ComposeSyncService.MANAGED_KEY) != null }
}
