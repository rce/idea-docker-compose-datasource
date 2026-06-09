plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "fi.rce.idea.datasources"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2024.3")
        bundledPlugins("com.intellij.database", "org.jetbrains.plugins.yaml")

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // Tests run inside the IDE test fixture (SnakeYAML, etc. come from the platform).
    // The platform bootstraps a JUnit 4 environment, so junit4 must be present even
    // though our tests are written with JUnit 5 (Jupiter).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Runs the JUnit3-style BasePlatformTestCase integration test under the JUnit
    // Platform launcher alongside the Jupiter unit tests.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")
}

// Computed eagerly at configuration time (a plain String) so it is safe to store in
// the Gradle configuration cache, unlike a provider lambda that captures the script.
val changelogFile = layout.projectDirectory.file("CHANGELOG.md").asFile
val changeNotesHtml: String =
    if (changelogFile.exists()) latestChangelogHtml(changelogFile.readText()) else ""

intellijPlatform {
    pluginConfiguration {
        vendor {
            name = "Henry Heikkinen"
            email = "rce@rce.fi"
            url = "https://github.com/rce/idea-docker-compose-datasource"
        }
        // Show the notes for the most recent CHANGELOG version on the listing.
        changeNotes = changeNotesHtml
        ideaVersion {
            sinceBuild = "243"
            // No upper bound, so the plugin isn't blocked on newer IDEs. Compiled
            // against the 2024.3 SDK but only uses stable APIs; verifyPlugin guards.
            untilBuild = provider { null }
        }
    }

    // JetBrains Marketplace requires signed plugins. Secrets come from the
    // environment (or ~/.gradle/gradle.properties) — never commit them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Publish to the "default" (stable) channel unless -PpublishChannel=<name> is
        // given (e.g. "eap" or "beta" for pre-releases).
        channels = providers.gradleProperty("publishChannel")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

/** Renders the bullet list under the top-most `## [x.y.z]` section as simple HTML. */
fun latestChangelogHtml(changelog: String): String {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return ""
    val rest = lines.drop(start + 1)
    val end = rest.indexOfFirst { it.startsWith("## ") }
    val section = if (end < 0) rest else rest.take(end)
    val items = section
        .map { it.trimStart() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { "<li>${it.drop(2).trim()}</li>" }
    return if (items.isEmpty()) "" else "<ul>${items.joinToString("")}</ul>"
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Auto-open the bundled sample project when running the sandbox IDE, so the
// startup activity has compose files to find without depending on which project
// the sandbox last reopened.
tasks.named<JavaExec>("runIde") {
    val sampleProject = layout.projectDirectory.dir("samples").asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider { listOf(sampleProject) })
    // Startup activities are suppressed for untrusted projects; auto-trust in the
    // sandbox so the auto-scan can run without a manual "Trust project" click.
    systemProperty("idea.trust.all.projects", "true")
}
