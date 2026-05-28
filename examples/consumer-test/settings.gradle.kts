plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "consumer-test"

dependencyResolutionManagement {
  repositories {
    // mavenLocal first so the SNAPSHOT we just published is found before
    // hitting Maven Central (where it doesn't exist).
    mavenLocal()
    mavenCentral()
  }
}
