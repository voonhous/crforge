plugins {
    `java-library`
}

dependencies {
    // The client schema is JSON
    implementation(libs.jackson.databind)
}
