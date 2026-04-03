plugins {
    java
    idea
    id("com.diffplug.spotless") version "8.4.0"
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
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        testImplementation(libs.findLibrary("junit-jupiter").get())
        testImplementation(libs.findLibrary("assertj-core").get())
        testImplementation(libs.findLibrary("mockito-core").get())
        testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
    }
}