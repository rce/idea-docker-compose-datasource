plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.github.dockercomposedatasource"
version = "0.1.0"

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

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "251.*"
        }
    }
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
