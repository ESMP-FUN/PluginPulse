import proguard.gradle.ProGuardTask

// Companion updater plugin: a standalone Paper/Spigot plugin that runs
// PluginPulse checks/staging on behalf of other installed plugins listed in its
// config.yml. Shades and relocates pluginpulse-core so it ships as one drop-in jar,
// the same jar for every supported Minecraft version (1.20.5 to 26.x).
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // 7.9.1+ reads Java 25 class files.
        classpath("com.guardsquare:proguard-gradle:7.10.0")
    }
}

plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    // Only for the Spigot API ProGuard reads; it may serve nothing else.
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        content { includeGroup("org.spigotmc") }
    }
}

dependencies {
    implementation(project(":pluginpulse-core"))
    // Bundled so the companion can offer no-restart installs (hot-reload: true)
    // for the plugins it manages. Relocated with core under ppcompanion.pulse.
    implementation(project(":pluginpulse-hotreload"))
}

// Its own root package is io.github.darkstarworks.ppcompanion, so relocating the
// core's io.github.darkstarworks.pluginpulse.* under it can't clobber our own
// classes (no shared prefix).
val relocated = "io.github.darkstarworks.ppcompanion.pulse"

// The unshaded jar stays out of build/libs, where the shipped jar goes.
tasks.jar {
    destinationDirectory.set(layout.buildDirectory.dir("plain"))
}

// The full jar before ProGuard; only an input for proguardJar.
tasks.shadowJar {
    archiveClassifier.set("full")
    destinationDirectory.set(layout.buildDirectory.dir("shadow"))
    from(rootProject.file("SOURCE-AVAILABLE.txt"))
    relocate("io.github.darkstarworks.pluginpulse", relocated)
    exclude("META-INF/maven/**", "META-INF/versions/**", "module-info.class")
}

// Spigot's API is read before Paper's, so the stack frames ProGuard works out name
// Bukkit types as Spigot has them. Otherwise one can name a Paper-only type and
// Spigot refuses to load that class.
val proguardSpigot: Configuration = configurations.create("proguardSpigot")
val proguardPaper: Configuration = configurations.create("proguardPaper")

dependencies {
    proguardSpigot("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT") { isTransitive = false }
    proguardPaper("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

val shippedJar = layout.buildDirectory.file("libs/${project.name}-$version.jar")

val proguardJar = tasks.register<ProGuardTask>("proguardJar") {
    dependsOn(tasks.shadowJar)
    injars(tasks.shadowJar.flatMap { it.archiveFile })
    outjars(shippedJar)
    configuration(file("proguard-rules.pro"))

    // JDK 21's modules: the oldest Java the jar runs on.
    val jmods = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.dir("jmods") }
    listOf("java.base", "java.logging", "java.net.http")
        .forEach { m -> libraryjars(jmods.map { it.file("$m.jmod") }) }
    libraryjars(proguardSpigot)
    libraryjars(proguardPaper)
}

tasks.assemble {
    dependsOn(proguardJar)
}

// api-version is the oldest line the jar runs on; newer servers load it as is.
tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
