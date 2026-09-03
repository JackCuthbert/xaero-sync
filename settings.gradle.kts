pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }
}

rootProject.name = "xaero-sync"

include(":shared")
include(":fabric-client")
include(":paper-plugin")

project(":shared").projectDir = file("packages/shared")
project(":fabric-client").projectDir = file("packages/fabric-client")
project(":paper-plugin").projectDir = file("packages/paper-plugin")
