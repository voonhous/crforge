plugins {
    application
    `java-library`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":gym-bridge"))

    // ZMQ + JSON (needed directly since gym-bridge uses implementation scope)
    implementation(libs.jeromq)
    implementation(libs.jackson.databind)

    // LibGDX
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    implementation(libs.gdx.freetype)
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:${libs.versions.gdx.get()}:natives-desktop")

    // Logging
    api(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

application {
    mainClass.set("org.crforge.desktop.DesktopLauncher")

    // macOS requires this for LWJGL/GLFW
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}

// The debug visualizer runs the battle core, which reads the game tables of one data version.
// The run passes the program these system properties, each from the Gradle property of the same
// name (for example in ~/.gradle/gradle.properties, or -P<name>=<value>) or its environment
// variable:
// - crforge.dataRoot (CRFORGE_DATA_ROOT): a checkout of the game data repository, one folder per
//   data version; without it, the crforge-data folder beside the project folder;
// - crforge.dataVersion: the data version to open, ahead of everything else;
// - crforge.gameTables (CRFORGE_GAME_TABLES): a tables folder named outright, the same setting the
//   test tasks are given; used when no data version is asked for;
// - crforge.projectDir: this project's folder, whose crforge-data.lock names the default version.
// See org.crforge.desktop.DataSelection for the order they are taken in.
tasks.named<JavaExec>("run") {
    systemProperty("crforge.projectDir", rootProject.projectDir.absolutePath)
    val settings =
        mapOf(
            "crforge.dataRoot" to "CRFORGE_DATA_ROOT",
            "crforge.dataVersion" to null,
            "crforge.gameTables" to "CRFORGE_GAME_TABLES",
        )
    for ((name, variable) in settings) {
        val value = (findProperty(name) as String?) ?: variable?.let { System.getenv(it) }
        if (value != null) {
            systemProperty(name, value)
        }
    }
}
