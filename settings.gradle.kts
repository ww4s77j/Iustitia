pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net")
        maven("https://maven.isxander.dev/releases")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
        id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
        id("dev.kikugie.loom-back-compat") version "0.3"
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin_version")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version") apply false
    id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version") apply false
    id("dev.kikugie.loom-back-compat") version "0.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Minecraft splits into two build worlds at the 26.1 boundary:
//  - <= 1.21.11 ships obfuscated and is built through the classic remapping
//    pipeline (fabric-loom-remap) with Yarn mappings.
//  - 26.x ships non-obfuscated with the official names baked in and is built
//    through fabric-loom directly.
// `loomx` (from loom-back-compat) applies the right flavour per version
// subproject, so the central build script can use one `loom {}` block.
extra["loomx.loom_version"] = providers.gradleProperty("loom_version").get()
extra["loomx.loom_remap_plugin"] = "net.fabricmc.fabric-loom-remap"
extra["loomx.loom_unobf_plugin"] = "net.fabricmc.fabric-loom"

loomx {
    unobfuscated { project -> project.name.startsWith("26.") }
}

// The version list lives in versions.json; per-version dependency coordinates
// live in stonecutter.properties.toml. See stonecutter.gradle.kts for the
// active version.
stonecutter {
    create(rootProject) {
        load(file("versions.json"))
    }
}

rootProject.name = "iustitia"
