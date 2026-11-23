// A separate Gradle build that uses the published library, as any JVM project would.
// Publish it locally first: cd ../../android && ./gradlew -Ptether.libraryOnly=true :core:publishToMavenLocal
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io") // for a released version: com.github.amirwmr.tether:tether-core:<tag>
    }
}
rootProject.name = "tether-hello-jvm"
