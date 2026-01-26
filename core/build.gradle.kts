plugins {
    `java-library`
}

dependencies {
    // JSON parsing for card data
    api("com.fasterxml.jackson.core:jackson-databind:${property("jacksonVersion")}")

    // Logging
    api("org.slf4j:slf4j-api:2.0.12")

    // Use simple logger for now in tests/CLI
    testImplementation("org.slf4j:slf4j-simple:2.0.12")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    testCompileOnly("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")
}