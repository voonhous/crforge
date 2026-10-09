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

/** A setting from a Gradle property, else from an environment variable. */
fun setting(property: String, variable: String): String? =
    (findProperty(property) as String?) ?: System.getenv(variable)

tasks.test {
    // A checkout of the game data repository: the decoder's tables are held to its tables, built
    // from its copy of the asset files. Without one the test that needs it is skipped.
    val dataRoot = setting("crforge.dataRoot", "CRFORGE_DATA_ROOT")
    inputs.property("crforge.dataRoot", dataRoot ?: "")
    if (dataRoot != null) {
        systemProperty("crforge.dataRoot", dataRoot)
    }
    maxHeapSize = "2g"
}

// Builds the game tables of a data version on this machine and prints their folder, the folder to
// name as crforge.gameTables:
//   ./gradlew -q :tables:gameTables [-Pcrforge.dataVersion=<version>] [-Pcrforge.assetSource=<URI>]
// - crforge.dataVersion: the data version, by default the version of crforge-data.lock;
// - crforge.assetSource (CRFORGE_ASSET_SOURCE): where the game's files are fetched from, by
//   default the game's asset CDN; a file: URI names a local copy laid out the same way, such as
//   the cdn/ folder of the game data repository;
// - crforge.cache (CRFORGE_CACHE): the cache of fetched files and built tables, by default
//   ~/.crforge. A file is fetched once, and a data version's tables are built once.
tasks.register<JavaExec>("gameTables") {
    group = "build"
    description = "Builds the game tables of a data version from the game's files and prints their folder."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.crforge.tables.TableBuild")
    val lock = rootProject.file("crforge-data.lock")
    val version =
        (findProperty("crforge.dataVersion") as String?)
            ?: lock.readLines().firstOrNull { it.startsWith("version=") }?.removePrefix("version=")
            ?: error("no data version: set crforge.dataVersion or version= in crforge-data.lock")
    val source = setting("crforge.assetSource", "CRFORGE_ASSET_SOURCE")
    args(listOfNotNull(version, source))
    setting("crforge.cache", "CRFORGE_CACHE")?.let { systemProperty("crforge.cache", it) }
}
