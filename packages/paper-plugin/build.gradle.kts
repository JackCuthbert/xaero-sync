import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

base {
    archivesName.set("xaero-sync-paper-26.3")
}

dependencies {
    val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse(libs.versions.paper).get()
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    implementation(project(":shared"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
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
