// Companion updater plugin for Velocity proxies: keeps the other plugins on the
// proxy up to date, listed in its config.yml. Shades and relocates the library
// so it ships as one drop-in jar.
plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

val velocityApiVersion = rootProject.extra["velocityApiVersion"] as String

dependencies {
    implementation(project(":pluginpulse-velocity"))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
}

// Same relocation as the Paper companion; its own package can't clash with it.
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("io.github.darkstarworks.pluginpulse", "io.github.darkstarworks.ppcompanion.pulse")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("velocity-plugin.json") {
        expand(props)
    }
}
