/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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

// The header every source file starts with (Java and the Gradle Kotlin scripts). spotlessApply
// adds it and spotlessCheck, run by the build and CI, fails a file without it. Its last line asks
// anyone porting crforge's logic to cite the file and commit they read (README, "Porting or
// reimplementing crforge"); it is a request, the license is Apache 2.0 unchanged.
val sourceHeader =
    """
    /*
     * crforge - https://github.com/voonhous/crforge
     * SPDX-License-Identifier: Apache-2.0
     * Porting this code? Please cite this file and the commit you read: see the README.
     */
    """.trimIndent() + "\n\n"

// A Gradle Kotlin script's header goes above its first import or plugins block.
val gradleScriptHeaderDelimiter = "(import |plugins )"

// The root project's own scripts (build.gradle.kts, settings.gradle.kts); each subproject's
// spotless block below covers its build.gradle.kts.
spotless {
    kotlinGradle {
        target("*.gradle.kts")
        licenseHeader(sourceHeader, gradleScriptHeaderDelimiter)
    }
}

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
            licenseHeader(sourceHeader)
        }
        kotlinGradle {
            licenseHeader(sourceHeader, gradleScriptHeaderDelimiter)
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