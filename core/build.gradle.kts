plugins {
    `java-library`
}

dependencies {
    // JSON parsing for card data
    api(libs.jackson.databind)

    // Logging
    api(libs.slf4j.api)
    // Use simple logger for now in tests/CLI
    testImplementation(libs.slf4j.simple)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
