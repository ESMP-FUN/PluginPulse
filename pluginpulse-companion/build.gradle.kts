// Companion updater plugin: a standalone Paper/Spigot plugin that runs
// PluginPulse checks/staging on behalf of other installed plugins listed in its
// config.yml. Shades and relocates pluginpulse-core so it ships as one drop-in jar.
plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":pluginpulse-core"))
}

// Its own root package is io.github.darkstarworks.ppcompanion, so relocating the
// core's io.github.darkstarworks.pluginpulse.* under it can't clobber our own
// classes (no shared prefix).
val relocated = "io.github.darkstarworks.ppcompanion.pulse"

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("io.github.darkstarworks.pluginpulse", relocated)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Inject the project version into plugin.yml.
tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
