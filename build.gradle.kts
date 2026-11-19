import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    `java-library`
    `maven-publish`
}

// One build for every supported Minecraft version. The library and the companion
// are compiled against the oldest Paper API they support (1.21.1) on JDK 21;
// everything version-sensitive is found by reflection at runtime and fails soft.
//
// ./gradlew build -PcheckApi=26.3.build.249-stable compiles the Paper-side
// modules against another Paper API instead (on JDK 25, which Paper 26 needs),
// to see whether a new Minecraft version breaks anything. Such a build is only
// for that check, never for release.
// TODO(26.4): no Paper 26.4 API is published yet. Run the -PcheckApi build against it once it is.
val checkApi = findProperty("checkApi") as String?
val paperApiVersion: String = checkApi ?: "1.21.1-R0.1-SNAPSHOT"
val jdkVersion: Int = if (checkApi != null && !checkApi.startsWith("1.")) 25 else 21

// Built against the oldest Velocity API still in use (3.4, Java 21). The same
// calls exist unchanged up to 4.3, so one jar covers every current proxy.
val velocityApiVersion = "3.4.0"

// Modules that must never see the Paper API: the shared code and the Velocity side.
val paperFreeModules = setOf("pluginpulse-common", "pluginpulse-velocity", "pluginpulse-companion-velocity")

extra["velocityApiVersion"] = velocityApiVersion

allprojects {
    group = "io.github.darkstarworks"
    version = "0.11.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(jdkVersion)
        }
        withSourcesJar()
        withJavadocJar()
    }

    // Keep bytecode at Java 21 even on the JDK 25 toolchain, so one variant isn't
    // needlessly locked to newer servers.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    dependencies {
        if (project.name !in paperFreeModules) {
            compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
            testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
        }

        testImplementation(platform("org.junit:junit-bom:6.1.3"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    // Paper 26 publishes Java 25 jars only; the bytecode stays Java 21, so Gradle
    // would refuse them on a -PcheckApi build without this.
    if (jdkVersion == 25) {
        configurations.matching {
            it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
        }.configureEach {
            attributes {
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
            }
        }
    }

    tasks.test {
        useJUnitPlatform()
    }

    tasks.javadoc {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
