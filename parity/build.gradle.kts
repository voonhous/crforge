plugins {
    application
}

dependencies {
    implementation(project(":core"))

    // The scenario, the observations and the manifest are JSON
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("org.crforge.parity.ReplaySmokeRun")
}
