// Companion updater plugin: a standalone Paper/Spigot plugin that runs
// PluginPulse checks/staging on behalf of other installed plugins listed in its
// config.yml. Shades and relocates pluginpulse-core so it ships as one drop-in jar.
plugins {
    id("com.gradleup.shadow") version "9.0.0"
}

dependencies {
    implementation(project(":pluginpulse-core"))
}

// Its own root package is io.github.darkstarworks.ppcompanion, so relocating the
// core's io.github.darkstarworks.pluginpulse.* under it can't clobber our own
// classes (no shared prefix).
val relocated = "io.github.darkstarworks.ppcompanion.pulse"

// Variant selected with -PmcVariant=26 (see the root build). mc26 gets an
// "-mc26" jar and api-version 26.1; the default keeps api-version 1.20.
val isMc26 = (findProperty("mcVariant") as String? ?: "1.21") == "26"
val pluginApiVersion = if (isMc26) "26.1" else "1.20"

tasks.shadowJar {
    archiveClassifier.set(if (isMc26) "mc26" else "")
    relocate("io.github.darkstarworks.pluginpulse", relocated)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Inject the project version + variant api-version into plugin.yml.
tasks.processResources {
    val props = mapOf("version" to version, "apiVersion" to pluginApiVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
