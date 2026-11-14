import org.gradle.api.logging.Logging
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// version.txt holds the version alone; the release pull request that release-please keeps open raises it, so it is
// not edited by hand
val pluginVersion = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }

group = providers.gradleProperty("pluginGroup").get()
version = pluginVersion.get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // no DefaultImpls bridges: implementing a platform interface must not override its deprecated defaults
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2026.3")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.modules.jna")
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.test {
    // a test JVM that touches Swing would otherwise become a macOS app and pull the screen over to its own desktop
    systemProperty("java.awt.headless", "true")
    // the end-to-end tests run the fake claude from here, so a change to it alone must run them again
    inputs.dir(layout.projectDirectory.dir("tools/fake-claude"))
        .withPropertyName("fakeClaude")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.runIde {
    // a sandbox IDE opened on a project path should not stop at the trust dialog
    jvmArgs("-Didea.trust.all.projects=true")
}

// UI tests drive an IDE that runs elsewhere, started with runIdeForUiTests below, through JetBrains' Remote Robot. They
// are black-box: no plugin classes on their classpath, and neither `test` nor `check` runs them
val uiTest: SourceSet by sourceSets.creating
val uiTestImplementation: Configuration by configurations.getting
dependencies {
    uiTestImplementation("org.jetbrains.kotlin:kotlin-stdlib")
    uiTestImplementation("junit:junit:4.13.2")
    uiTestImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    uiTestImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")
}
tasks.register<Test>("uiTest") {
    description = "Runs the UI tests against an IDE started with runIdeForUiTests (robot server at -ProbotUrl, " +
        "http://127.0.0.1:8082 by default)."
    group = "verification"
    testClassesDirs = uiTest.output.classesDirs
    classpath = uiTest.runtimeClasspath
    systemProperty("robot.url", providers.gradleProperty("robotUrl").getOrElse("http://127.0.0.1:8082"))
    systemProperty("ui.screenshots", layout.buildDirectory.dir("reports/ui-screenshots").get().asFile.absolutePath)
    // the Remote Robot client reads its answers with Gson, which reaches into Throwable on this JDK
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    outputs.upToDateWhen { false }
}

// An IDE with JetBrains' robot server, for UI tests driven from outside (Remote Robot). It is started by hand or on a
// test machine and never by a build, so `test` and `check` do not depend on it
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Didea.trust.all.projects=true",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Dide.show.tips.on.startup.default.value=false",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                    )
                }
            }
            plugins {
                // the same version as the Remote Robot client of the UI tests below
                robotServerPlugin("0.11.23")
            }
        }
    }
}

// CHANGELOG.md is release-please's: "## [0.5.1](compare link) (date)" headers, "### Features" and "### Bug Fixes"
// sections, "* " items and no Unreleased section. The build only reads it
changelog {
    itemPrefix = "*"
    groups = listOf("Features", "Bug Fixes")
}

// patchChangelog would rewrite CHANGELOG.md in the changelog plugin's own format, so it stays off. The IntelliJ
// Platform plugin also makes publishPlugin depend on it, and in the task graph its changelog input is read for the
// configuration cache, where a CHANGELOG.md the plugin cannot parse would stop a Marketplace upload; so it leaves
// that graph as well
tasks.patchChangelog {
    enabled = false
}
tasks.publishPlugin {
    setDependsOn(dependsOn.filterNot { it == "patchChangelog" })
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        // the changelog section of the version being built, or the newest one while that version has none yet. The
        // provider below uses local values only, so it never captures the project (configuration cache)
        val changelog = project.changelog
        // release-please ends each item with links to its pull request and commit, and a "closes" list for the
        // issues it closed; the IDE's plugin page shows the notes to users, who have no use for them
        val references = listOf(
            Regex(""" \(\[[^\]]+]\([^)\s]+\)\)"""),
            Regex(""", closes( \[[^\]]+]\([^)\s]+\))+"""),
        )
        // a CHANGELOG.md the changelog plugin cannot read, or one with nothing to say for this version, must not
        // stop a build or a release: the notes then point at the file and the build log says why
        val fallback = """<p>See <a href="https://github.com/v0rest/skein-intellij-claude-code/blob/main/CHANGELOG.md">""" +
            """CHANGELOG.md</a>.</p>"""
        changeNotes = pluginVersion.map { version ->
            try {
                with(changelog) {
                    val item = (getOrNull(version) ?: getLatest())
                        .withHeader(false)
                        .withEmptySections(false)
                        .withLinks(false)
                    val notes = references.fold(renderItem(item, Changelog.OutputType.MARKDOWN)) { text, links ->
                        text.replace(links, "")
                    }
                    check(notes.isNotBlank()) { "the section it chose for $version has no items" }
                    markdownToHTML(notes)
                }
            } catch (problem: Exception) {
                // Gradle wraps the changelog plugin's own exception, whose message says what is wrong
                val reason = generateSequence<Throwable>(problem) { it.cause }.last()
                Logging.getLogger("changeNotes").warn(
                    "w: CHANGELOG.md gives no change notes for $version ({}); the plugin points at the file instead",
                    reason.message ?: reason.javaClass.simpleName,
                )
                fallback
            }
        }
        ideaVersion {
            sinceBuild = "263"
        }
    }
    pluginVerification {
        ides {
            if (providers.gradleProperty("verifyNewestIdes").orNull == "true") {
                // the weekly Newest IDE workflow: every IntelliJ IDEA release, RC and EAP from the since-build on,
                // so a new IDE that breaks Skein shows up there first; no build or release depends on it
                recommended()
            } else {
                // the IDE the plugin is built against; recommended() has no upper bound without an until-build, so
                // every new IDE release would join the gate (and the download) on its own, turning builds red with no
                // change
                create(IntelliJPlatformType.IntellijIdea, "2026.3")
            }
        }
        // the Marketplace rejects internal API usage, so the build does too
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
    }
    // room for the JetBrains Marketplace, read only by publishPlugin, which the Build workflow's marketplace job runs
    // when the repository variable PUBLISH_TO_MARKETPLACE is 'true'; without these variables signPlugin skips itself
    // and the rest of the build never reads them
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
