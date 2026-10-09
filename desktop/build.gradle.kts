plugins {
    application
    `java-library`
}

dependencies {
    implementation(project(":core"))

    // The configured game tables, their shipped rows and the synthetic replay scenarios of the
    // core's test fixtures
    testImplementation(testFixtures(project(":core")))

    // JSON (data selection and replay files)
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

// The debug visualizer runs the battle core, which reads the game tables of one data version. The
// run builds them first (:tables:gameTables, see the root build for crforge.assetSource and the
// other settings) and passes the program, as system properties, the folder they are built in
// (crforge.tablesRoot, one folder per data version, the versions the V key cycles through), the
// data version to open (crforge.dataVersion: -Pcrforge.dataVersion=<v>, by default the lock's) and
// the lock's version (crforge.lockVersion). See org.crforge.desktop.DataSelection.
tasks.named<JavaExec>("run") {
    dependsOn(":tables:gameTables")
    systemProperty("crforge.tablesRoot", rootProject.extra["crforge.builtTablesRoot"] as String)
    systemProperty("crforge.dataVersion", rootProject.extra["crforge.dataVersion"] as String)
    systemProperty("crforge.lockVersion", rootProject.extra["crforge.lockVersion"] as String)
}

// Opt-in graphics integration checks. Requires a display/OpenGL; never part of headless check.
tasks.register<JavaExec>("uiSmoke") {
    group = "verification"
    description = "Exercise workspace input and layout in a hidden LWJGL window (the built tables)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.crforge.desktop.WorkspaceSmoke")
    if (System.getProperty("os.name").lowercase().contains("mac")) jvmArgs("-XstartOnFirstThread")
    systemProperty("crforge.uiSmokeOutput", layout.buildDirectory.dir("ui-smoke").get().asFile.absolutePath)
    // the tables the build makes, passed as to the run task
    dependsOn(":tables:gameTables")
    systemProperty("crforge.tablesRoot", rootProject.extra["crforge.builtTablesRoot"] as String)
    systemProperty("crforge.dataVersion", rootProject.extra["crforge.dataVersion"] as String)
    systemProperty("crforge.lockVersion", rootProject.extra["crforge.lockVersion"] as String)
}
