/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

plugins {
    `java-library`
}

dependencies {
    // The client schema, the fingerprints and the game tables are JSON
    implementation(libs.jackson.databind)
    // The game's TOML files
    implementation(libs.jackson.dataformat.toml)
    // The game's files are LZMA streams
    implementation(libs.xz)
}

tasks.test {
    // A checkout of the game data repository (-Pcrforge.dataRoot=<dir>): the decoder's tables are
    // held to its tables, built from its copy of the asset files. Without one that test is skipped.
    val dataRoot = (findProperty("crforge.dataRoot") as String?)?.takeIf { it.isNotBlank() }
    inputs.property("crforge.dataRoot", dataRoot ?: "")
    if (dataRoot != null) {
        systemProperty("crforge.dataRoot", dataRoot)
    }
    maxHeapSize = "2g"
}

// Builds the game tables on this machine into the cache and prints each folder:
//   ./gradlew -q :tables:gameTables [-Pcrforge.dataVersion=<version>]
// It builds the data version (by default the version of crforge-data.lock) and every version the
// lock lists as compatible, each in its own folder of <cache>/tables. Every task that needs the
// tables depends on it. Its settings are the root build's: crforge.assetSource (required: a file:
// URI of a copy of the asset files laid out as the CDN, or "cdn" for the game's asset CDN; without
// it the task stops, naming it) and crforge.cache. A file is fetched once, and a data version's
// tables are built once.
tasks.register<JavaExec>("gameTables") {
    group = "build"
    description = "Builds the game tables from the game's files and prints their folders."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.crforge.tables.TableBuild")
    @Suppress("UNCHECKED_CAST")
    val versions = rootProject.extra["crforge.tablesVersions"] as List<String>
    val source = rootProject.extra["crforge.assetSource"] as String?
    // an empty source makes the program stop and name the setting
    args(listOf(rootProject.extra["crforge.cache"] as String, source ?: "") + versions)
}
