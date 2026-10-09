import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
}

dependencies {
    implementation(project(":core"))

    // The scenario, the observations and the manifest are JSON
    implementation(libs.jackson.databind)

    // The synthetic replay scenarios of the core's test fixtures
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("org.crforge.conformance.ReplaySmokeRun")
}

// The recorded reference battles live outside the repository, in the game data repository at the
// commit crforge-data.lock pins: references/<version>. A folder of them is named by the Gradle
// property -Pcrforge.references=<dir>; without one the reference test is skipped. The tables are the
// ones the build makes for -Pcrforge.dataVersion (see the root build), which must be the
// references' version. The expected outcome of every case is kept here, one file per content
// version.
val expectationsFolder = layout.projectDirectory.dir("reference-expectations")

/** A Gradle property's value, or null when it is not set or blank. */
fun setting(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

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
    listOf(
        "crforge.references",
        "crforge.references.shard",
        "crforge.references.shards",
        "crforge.references.threads",
    ).forEach { name -> setting(name)?.let { systemProperty(name, it) } }
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
    // the tables the build makes for the data version, as the test tasks read them (root build)
    dependsOn(":tables:gameTables")
    systemProperty(
        "crforge.gameTables",
        "${rootProject.extra["crforge.builtTablesRoot"]}/${rootProject.extra["crforge.dataVersion"]}",
    )
    setting("crforge.references")?.let { systemProperty("crforge.references", it) }
    args("--expectations", expectationsFolder.asFile.absolutePath)
}
