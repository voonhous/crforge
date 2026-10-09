plugins {
    java
    idea
    id("com.diffplug.spotless") version "8.10.1"
}

/** A Gradle property's value, or null when it is not set or blank. */
fun setting(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

// The game tables are not in this repository. There is one way to get them: the build makes them
// from the game's files (:tables:gameTables) into the cache, and every task that needs them reads
// them from there (see docs/game-tables.md, Building the game tables). The settings, Gradle
// properties only (-P<name>=<value>, or a line in ~/.gradle/gradle.properties):
// - crforge.assetSource, required: where the game's files are read from, a file: URI of a copy
//   laid out as the CDN, or "cdn" for the game's asset CDN. Without it the build stops, naming it;
// - crforge.dataVersion: the data version to use, by default the version= of crforge-data.lock;
// - crforge.cache: the cache the files are fetched into and the tables built in, by default
//   ~/.crforge.
val lockLines = file("crforge-data.lock").readLines()

fun lockValue(key: String): String? =
    lockLines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()

val lockVersion = lockValue("version") ?: error("crforge-data.lock has no version=")
val dataVersion = setting("crforge.dataVersion") ?: lockVersion
val compatibleVersions = (lockValue("compatible") ?: lockVersion).split(" ").filter { it.isNotBlank() }
val cacheRoot = setting("crforge.cache") ?: "${System.getProperty("user.home")}/.crforge"
val builtTablesRoot = "$cacheRoot/tables"

extra["crforge.dataVersion"] = dataVersion
extra["crforge.lockVersion"] = lockVersion
extra["crforge.tablesVersions"] = (compatibleVersions + dataVersion).distinct()
extra["crforge.assetSource"] = setting("crforge.assetSource")
extra["crforge.cache"] = cacheRoot
extra["crforge.builtTablesRoot"] = builtTablesRoot

allprojects {
    group = "org.crforge"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    spotless {
        java {
            googleJavaFormat("1.25.2")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // The tests read the tables the build makes for the data version (and every compatible
        // one, beside it), named by the crforge.gameTables system property. The tables module's
        // own tests build theirs themselves.
        if (project.name != "tables") {
            val gameTables = "$builtTablesRoot/$dataVersion"
            dependsOn(":tables:gameTables")
            inputs.dir(gameTables).withPropertyName("gameTables")
            systemProperty("crforge.gameTables", gameTables)
        }
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        testImplementation(libs.findLibrary("junit-jupiter").get())
        testImplementation(libs.findLibrary("assertj-core").get())
        testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
    }
}