// Legacy buildscript-classpath style: resolves AGP/Kotlin from the cached jars directly, avoiding
// the plugin-marker artifacts (not in the local cache, can't fetch offline).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
    }
    // AGP 8.13 transitively pulls some Kotlin artifacts at 2.0.21, whose binary jars aren't in the
    // offline cache. Pin the whole Kotlin group to 2.1.20 (fully cached) on the buildscript classpath.
    configurations.classpath {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.1.20")
            }
        }
    }
}
