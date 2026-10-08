plugins {
    `java-library`
    // Test helpers other modules' tests share, such as the game tables of a named data version
    `java-test-fixtures`
}

dependencies {
    // The battle core reads the game's own tables, which are JSON
    implementation(libs.jackson.databind)

    // Scenarios writes replay scenarios, which are JSON
    testFixturesImplementation(libs.jackson.databind)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// With test fixtures, the tests run against core's jar in place of its classes folder. The
// fidelity ledger scans the classes folder its own class was loaded from, so put it first.
tasks.test {
    classpath = sourceSets.main.get().output + classpath
}

// Prints how much of the simulation is established behaviour and how much is still inferred.
tasks.register<JavaExec>("fidelityReport") {
    group = "verification"
    description = "Prints the simulation fidelity ledger."
    mainClass.set("org.crforge.core.fidelity.FidelityLedger")
    classpath = sourceSets["main"].runtimeClasspath
}
