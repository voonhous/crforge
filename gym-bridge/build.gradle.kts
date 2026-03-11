plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    // JSON for bridge protocol
    implementation(libs.jackson.databind)

    // ZeroMQ for transport
    implementation(libs.jeromq)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("org.crforge.bridge.BridgeServer")
}
