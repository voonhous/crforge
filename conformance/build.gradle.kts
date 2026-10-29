/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
}

// The JMH benchmarks (src/jmh): their own source set on the main classes, run by tickBenchmark.
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation(project(":core"))

    // The scenario, the observations and the manifest are JSON
    implementation(libs.jackson.databind)

    // A reference battle's trace is zstd-compressed (TraceReader): a pure-Java zstd decoder
    implementation(libs.aircompressor)

    // The synthetic replay scenarios of the core's test fixtures
    testImplementation(testFixtures(project(":core")))

    // The tick benchmark: JMH and its annotation processor, which generates the benchmark harness
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator.annprocess)
}

application {
    mainClass.set("org.crforge.conformance.ReplaySmokeRun")
}

// The recorded reference battles live outside the repository, in the game data repository at the
// commit crforge-data.lock pins: references/<version>. A folder of them is named by the Gradle
// property -Pcrforge.references=<dir>; without one the reference test is skipped. The tables are the
// ones the build makes for -Pcrforge.dataVersion (see the root build), which must be the
// references' version; the reference test builds that version's alone (:tables:dataVersionTables).
// The expected outcome of every case is kept here, one file per content version.
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
    // A file tree, not a directory input: a data version whose references are new has no
    // expectations folder until updateReferenceExpectations writes it, and a missing directory
    // input fails the task's validation.
    inputs.files(fileTree(expectationsFolder)).withPropertyName("expectations")
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
    // the tables the build makes for the data version, as the test tasks read them (root build);
    // no other version's
    dependsOn(":tables:dataVersionTables")
    systemProperty(
        "crforge.gameTables",
        "${rootProject.extra["crforge.builtTablesRoot"]}/${rootProject.extra["crforge.dataVersion"]}",
    )
    setting("crforge.references")?.let { systemProperty("crforge.references", it) }
    args("--expectations", expectationsFolder.asFile.absolutePath)
}

// The benchmarks are compiled with every check, so they keep compiling as the battle core moves.
tasks.register<JavaExec>("coverageScore") {
    description =
        "Plays every battle of a data version's coverage set (-Pcrforge.coverage) and writes the " +
            "share the battle core reproduces, with its badge, to build/coverage/ (a measurement, " +
            "never a gate); with -Pcrforge.coverage.part=K only part K's battles, into their tally; " +
            "-Pcrforge.coverage.set=NAME names a second set of the version (16.635.3-challenge)."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.crforge.conformance.CoverageScore")
    maxHeapSize = "3g"
    // the data version's tables alone: the set is of that version
    dependsOn(":tables:dataVersionTables")
    val version = rootProject.extra["crforge.dataVersion"] as String
    systemProperty("crforge.gameTables", "${rootProject.extra["crforge.builtTablesRoot"]}/$version")
    setting("crforge.coverage")?.let { systemProperty("crforge.coverage", it) }
    args("--out-dir", layout.buildDirectory.dir("coverage").get().asFile.absolutePath)
    setting("crforge.coverage.part")?.let { args("--part", it) }
    // the set's name when the version has more than one set (16.635.3-challenge); else the version
    setting("crforge.coverage.set")?.let { args("--set", it) }
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("coverageMerge") {
    description =
        "Sums the tallies of a coverage set's parts (-Pcrforge.coverage.tallies, a folder of " +
            "tally-*.json) into the set's score and badge in build/coverage/, as one run would."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.crforge.conformance.CoverageScore")
    args("--out-dir", layout.buildDirectory.dir("coverage").get().asFile.absolutePath)
    setting("crforge.coverage.tallies")?.let { args("--merge", it) }
    outputs.upToDateWhen { false }
}

tasks.check { dependsOn(tasks.named(jmh.classesTaskName)) }

tasks.register<JavaExec>("tickBenchmark") {
    description =
        "Measures how many battle steps a second the battle core runs, headless, on the " +
            "scenarios of the recorded reference battles, with JMH (docs/performance.md)."
    group = "verification"
    classpath = jmh.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    maxHeapSize = "3g"
    // the tables the build makes for the data version, as the test tasks read them (root build);
    // no other version's
    dependsOn(":tables:dataVersionTables")
    // JMH's forks are started with this JVM's arguments, the two folders included.
    systemProperty(
        "crforge.gameTables",
        "${rootProject.extra["crforge.builtTablesRoot"]}/${rootProject.extra["crforge.dataVersion"]}",
    )
    setting("crforge.references")?.let { systemProperty("crforge.references", it) }
    // By default every benchmark with the allocation profiler and a JSON result file; --args gives
    // JMH's own options instead (for example --args="TickBenchmark.replays -f 1").
    val results = layout.buildDirectory.file("jmh/results.json").get().asFile
    args("-prof", "gc", "-rf", "json", "-rff", results.absolutePath)
    doFirst { results.parentFile.mkdirs() }
    outputs.upToDateWhen { false }
}
