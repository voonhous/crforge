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

// Prints how much of the simulation is established behaviour and how much is still inferred.
tasks.register<JavaExec>("fidelityReport") {
    group = "verification"
    description = "Prints the simulation fidelity ledger."
    mainClass.set("org.crforge.core.fidelity.FidelityLedger")
    classpath = sourceSets["main"].runtimeClasspath
}
