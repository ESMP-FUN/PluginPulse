// Velocity side of the library: the shared engine in pluginpulse-common wired to
// the proxy's scheduler, commands, events and console. Gson, Adventure,
// MiniMessage, SnakeYAML and SLF4J are all provided by Velocity.
val velocityApiVersion = rootProject.extra["velocityApiVersion"] as String

dependencies {
    api(project(":pluginpulse-common"))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
}
