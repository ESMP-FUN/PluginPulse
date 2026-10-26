// Platform-free half of the library: update sources, version compare, download
// pipeline, state files and the update engine. Gson is provided by both Paper
// and Velocity at runtime.
dependencies {
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
}
