/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

plugins {
    application
    `java-library`
}

// The JMH benchmarks (src/jmh): their own source set on the main classes, run by envBenchmark.
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    api(project(":core"))

    // The metadata and vocabulary exports
    implementation(libs.jackson.databind)

    // A crawl's zstd JSON Lines output, read by the replay labeller
    implementation(libs.aircompressor)

    // The configured game tables of the core's test fixtures
    testImplementation(testFixtures(project(":core")))

    // The environment benchmark: JMH and its annotation processor
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator.annprocess)
}

// installDist lays the module and the core out as jars under build/install/env/lib, which the
// Python package puts on the JVM's class path (python/crforge_env/_jvm.py).
application {
    mainClass.set("org.crforge.env.VectorEnv")
}

// The benchmarks are compiled with every check, so they keep compiling as the environment moves.
tasks.check { dependsOn(tasks.named(jmh.classesTaskName)) }

// Measures how many environment steps a second the vector environment runs, headless, on the data
// version's tables (docs/environment.md). --args gives JMH's own options, for example
// --args="VectorEnvBenchmark.synthetic -f 1".
tasks.register<JavaExec>("envBenchmark") {
    description =
        "Measures how many environment steps a second the vector environment runs, with JMH " +
            "(docs/environment.md)."
    group = "verification"
    classpath = jmh.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    maxHeapSize = "3g"
    // the tables the build makes for the data version, as the test tasks read them (root build);
    // the synthetic benchmark reads none, so a run of it alone may skip the tables with
    // -x :tables:dataVersionTables
    dependsOn(":tables:dataVersionTables")
    systemProperty(
        "crforge.gameTables",
        "${rootProject.extra["crforge.builtTablesRoot"]}/${rootProject.extra["crforge.dataVersion"]}",
    )
    val results = layout.buildDirectory.file("jmh/results.json").get().asFile
    args("-prof", "gc", "-rf", "json", "-rff", results.absolutePath)
    doFirst { results.parentFile.mkdirs() }
    outputs.upToDateWhen { false }
}
