plugins {
    application
}

val gdxVersion: String by project

dependencies {
    implementation(project(":core"))

    // LibGDX
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}

application {
    mainClass.set("org.crforge.desktop.DesktopLauncher")
}