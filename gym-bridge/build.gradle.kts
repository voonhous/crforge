plugins {
    application
}

dependencies {
    implementation(project(":core"))

    // JSON for bridge protocol
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("org.crforge.bridge.BridgeServer")
}
