// Reference injection engine + CLI. Takes a compiled plugin jar and wires
// PluginPulse into it (wrapper-subclass strategy, or in-place instrument for
// final main classes) without its source. Also the boot-testable reference the
// in-browser JS engine is golden-tested against.
plugins {
    id("com.gradleup.shadow") version "8.3.5"
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
