import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
}

dependencies {
    implementation(project(":core"))

    // The scenario, the observations and the manifest are JSON
    implementation(libs.jackson.databind)

    // The game tables of a named data version
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("org.crforge.conformance.ReplaySmokeRun")
}

// The recorded reference battles live outside the repository, in the game data repository at the
// commit crforge-data.lock pins: references/<version> beside the tables. A folder of them is named
// by -Pcrforge.references=<dir> or the CRFORGE_REFERENCES variable; without one the reference test
// is skipped. The expected outcome of every case is kept here, one file per content version.
val expectationsFolder = layout.projectDirectory.dir("reference-expectations")

/** A setting from a Gradle property, else from an environment variable. */
fun setting(property: String, variable: String): String? =
    (findProperty(property) as String?) ?: System.getenv(variable)

tasks.test {
    // The reference battles run in their own task, so a local test run stays fast.
    useJUnitPlatform { excludeTags("reference") }
}

val referenceTest by tasks.registering(Test::class) {
    description =
        "Runs every recorded reference battle and checks each case's outcome against " +
            "reference-expectations/<version>.json."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("reference") }
    shouldRunAfter(tasks.test)
    maxHeapSize = "3g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
    systemProperty("crforge.references.expectations", expectationsFolder.asFile.absolutePath)
    systemProperty(
        "crforge.references.scorecard",
        layout.buildDirectory.dir("reference-scorecard").get().asFile.absolutePath,
    )
    // The cases can be split across machines: a shard index (from 0) and the shard count.
    mapOf(
        "crforge.references" to setting("crforge.references", "CRFORGE_REFERENCES"),
        "crforge.references.shard" to setting("crforge.references.shard", "CRFORGE_REFERENCES_SHARD"),
        "crforge.references.shards" to
            setting("crforge.references.shards", "CRFORGE_REFERENCES_SHARDS"),
        "crforge.references.threads" to
            setting("crforge.references.threads", "CRFORGE_REFERENCES_THREADS"),
    ).forEach { (key, value) -> if (value != null) systemProperty(key, value) }
    inputs.dir(expectationsFolder)
    // The references are outside the build; the task is never up to date.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("updateReferenceExpectations") {
    description =
        "Runs every recorded reference battle and rewrites reference-expectations/<version>.json " +
            "from the outcomes, for review in the change that moves them."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.crforge.conformance.ReferenceSuite")
    maxHeapSize = "3g"
    mapOf(
        "crforge.gameTables" to setting("crforge.gameTables", "CRFORGE_GAME_TABLES"),
        "crforge.references" to setting("crforge.references", "CRFORGE_REFERENCES"),
    ).forEach { (key, value) -> if (value != null) systemProperty(key, value) }
    args("--expectations", expectationsFolder.asFile.absolutePath)
}
