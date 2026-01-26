plugins {
    `java-library`
}

dependencies {
    // Tests need access to CardRegistry to build decks
    testImplementation(project(":data"))

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
