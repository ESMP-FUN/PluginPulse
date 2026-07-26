// Reference injection engine + CLI. Takes a compiled plugin jar and wires
// PluginPulse into it (wrapper-subclass strategy, or in-place instrument for
// final main classes) without its source. Also the boot-testable reference the
// in-browser JS engine is golden-tested against.
plugins {
    id("com.gradleup.shadow") version "9.0.0"
}

dependencies {
    implementation("me.lucko:jar-relocator:1.7")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("info.picocli:picocli:4.7.6")
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "io.github.darkstarworks.pluginpulse.inject.InjectCli"
    }
}

// Bundle the built core jar as a payload resource the injector reads at runtime.
tasks.processResources {
    dependsOn(":pluginpulse-core:jar")
    from(project(":pluginpulse-core").tasks.named("jar")) {
        // NB: not a ".jar" extension — the shadow plugin strips nested *.jar
        // resources from the fat jar. jar-relocator reads it as a zip regardless.
        rename { "pluginpulse-core.jar.payload" }
        into("payload")
    }
}

// The payload the in-browser engine relocates into a target jar: the core plus
// the hot-reload module, so a browser-injected plugin can offer no-restart
// installs like a CLI-injected or hand-shaded one. Both live under the same
// package prefix, so the engine's existing relocation covers them and the
// hot-reload classes stay inert unless pluginpulse.yml turns them on.
val webPayloadJar by tasks.registering(Jar::class) {
    dependsOn(":pluginpulse-core:jar", ":pluginpulse-hotreload:jar")
    archiveFileName.set("pluginpulse-web-payload.jar")
    destinationDirectory.set(layout.buildDirectory.dir("web-payload"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ zipTree(project(":pluginpulse-core").tasks.named("jar").get().outputs.files.singleFile) })
    from({ zipTree(project(":pluginpulse-hotreload").tasks.named("jar").get().outputs.files.singleFile) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC", "module-info.class")
}

// Regenerate the static assets the in-browser JS engine consumes:
//   docs/engine/wrapper-template.class   (placeholder-name wrapper, from ASM)
//   docs/engine/pluginpulse-core.jar     (un-relocated payload, relocated in-browser)
// Run this after ANY change to core or hotreload, or the browser tool will keep
// shipping the previous build's behaviour.
tasks.register<JavaExec>("emitWebAssets") {
    group = "pluginpulse"
    description = "Emit the wrapper template + core payload for the web engine into docs/engine."
    dependsOn(tasks.named("classes"), webPayloadJar)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.darkstarworks.pluginpulse.inject.TemplateEmitter")
    val outDir = rootProject.file("docs/engine")
    args(outDir.absolutePath)
    doLast {
        copy {
            from(webPayloadJar.get().archiveFile)
            into(outDir)
            // The engine fetches this name; kept as-is so the JS needs no change.
            rename { "pluginpulse-core.jar" }
        }
        println("Copied web payload to $outDir/pluginpulse-core.jar")
    }
}
