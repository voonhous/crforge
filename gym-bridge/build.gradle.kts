plugins {
    application
}

dependencies {
    implementation(project(":core"))

    // JSON for bridge protocol
    implementation("com.fasterxml.jackson.core:jackson-databind:${property("jacksonVersion")}")
}

application {
    mainClass.set("org.crforge.bridge.BridgeServer")
}