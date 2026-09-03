import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

base {
    archivesName.set("xaero-sync-paper-26.2")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":shared"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

val pluginVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", pluginVersion)

    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    relocate("kotlin", "io.github.jackcuthbert.xaerosync.paper.libs.kotlin")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
