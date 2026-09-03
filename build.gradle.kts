import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "io.github.jackcuthbert.xaerosync"
    version = providers.gradleProperty("modVersion").get()
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
}

tasks.register("verify") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs every project verification task."
    dependsOn(gradle.includedBuilds.map { it.task(":check") })
    dependsOn(subprojects.map { it.tasks.named("check") })
}
