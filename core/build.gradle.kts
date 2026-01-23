plugins {
    `java-library`
}

dependencies {
    // JSON parsing for card data
    api("com.fasterxml.jackson.core:jackson-databind:${property("jacksonVersion")}")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    testCompileOnly("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")
}