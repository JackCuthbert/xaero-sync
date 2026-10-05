plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName.set("xaero-sync-fabric-26.3")
}

loom {
    accessWidenerPath.set(file("src/main/resources/xaero-sync.accesswidener"))
}

dependencies {
    val minecraftVersion = providers.gradleProperty("minecraftVersion").orElse(libs.versions.minecraft).get()
    val fabricLoaderVersion = providers.gradleProperty("fabricLoaderVersion").orElse(libs.versions.fabric.loader).get()
    val fabricApiVersion = providers.gradleProperty("fabricApiVersion").orElse(libs.versions.fabric.api).get()
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation(libs.fabric.language.kotlin)
    implementation(project(":shared"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

val modVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", modVersion)

    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.jar {
    from(project(":shared").the<SourceSetContainer>().named("main").map { it.output })
}
