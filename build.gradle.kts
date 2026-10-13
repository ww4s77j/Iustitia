import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// -----------------------------------------------------------------------------
// Multi-version build (Stonecutter) — central build script.
//
// Stonecutter registers one Gradle subproject per Minecraft version
// (versions.json) and builds every one of them with this script. The `sc`
// accessor exposes the node currently being configured; per-version dependency
// coordinates come from stonecutter.properties.toml.
//
// Loom flavour selection (settings.gradle.kts via `loomx`):
//  - <= 1.21.11: fabric-loom-remap — obfuscated releases, Yarn mappings.
//  - 26.x:       fabric-loom       — non-obfuscated releases, official names.
// -----------------------------------------------------------------------------

plugins {
    id("dev.kikugie.loom-back-compat")
    id("org.jetbrains.kotlin.jvm")
}

// Version-scoped properties from stonecutter.properties.toml (e.g. deps.fabric_api)
fun scProperty(key: String): String = sc.properties[key]

version = "${property("mod.version")}+${sc.current.version}"
group = property("mod.group") as String
base { archivesName.set(property("mod.id") as String) }

// Minecraft requires Java 25 from 26.1 onwards; 1.20.5–1.21.11 target Java 21.
// The foojay resolver (settings.gradle.kts) provisions the JDK into Gradle's
// own cache when it is missing — nothing is installed system-wide.
val requiredJava: JavaVersion =
    if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21
val requiredJvmTarget: JvmTarget =
    if (requiredJava == JavaVersion.VERSION_25) JvmTarget.JVM_25 else JvmTarget.JVM_21

loom {
    mixin {
        // Kotlin mixins aren't seen by the legacy javac Mixin AP (no Java sources),
        // so it produces no refmap. The non-legacy path remaps mixin refs during
        // remapJar, which works for Kotlin mixin classes.
        useLegacyMixinAp = false
    }
}

repositories {
    maven("https://maven.isxander.dev/releases")   // YACL
    maven("https://maven.fabricmc.net")
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")

    // One naming world for every node: official (Mojang) names. Pre-26.1 releases
    // are obfuscated, so loomx applies Mojang's official mappings to them; 26.x
    // ships non-obfuscated with the same names baked in. This is what lets one
    // source tree compile across the boundary without per-version naming conditions.
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${scProperty("deps.fabric_api")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("deps.fabric_kotlin")}")
    modImplementation("dev.isxander:yet-another-config-lib:${scProperty("deps.yacl")}")

    // Pure-JVM unit tests (src/test) for config/preset logic that is separable from Minecraft
    // objects (docs/ai-assisted-development.md: add pure tests where the logic can be separated).
    // kotlin("test") resolves to its JUnit5 variant because the test task uses JUnitPlatform.
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(requiredJava.majorVersion.toInt())) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(requiredJava.majorVersion.toInt())
    compilerOptions { jvmTarget.set(requiredJvmTarget) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(requiredJava.majorVersion.toInt())
}

// -----------------------------------------------------------------------------
// Resource expansion: each jar declares its own Minecraft range, and the mixin
// compatibility level follows the Java target of the version being built.
// -----------------------------------------------------------------------------
val expansionProps = mapOf(
    "version" to project.version,
    "minecraft" to scProperty("mod.mc_compat"),
    "mixin_java" to "JAVA_${requiredJava.majorVersion}",
)

tasks.processResources {
    inputs.properties(expansionProps)
    filesMatching("fabric.mod.json") {
        expand(expansionProps)
    }
    filesMatching("iustitia.mixins.json") {
        expand(expansionProps)
    }
}

// -----------------------------------------------------------------------------
// Automated live tests (docs/automated-live-testing.md)
//
// STAGED: the gametest harness (src/gametest) and this wiring are still built
// against 1.21.11 APIs, so the test mod is registered for the 1.21.11 node
// only. Porting the harness to 26.x is a follow-up task; until then 26.x
// builds without the test mod and live verification runs on 1.21.11.
//
// The self-test harness lives in the `gametest` source set and runs through
// Fabric API's client gametest framework: Loom boots a real client, creates a
// deterministic flat world, runs the scenarios, and exits with a report — no
// human at the keyboard, no server, no packets leaving the machine.
//
// `./gradlew runClientGameTest`  — full three-pass verification (legit + cheat + replay)
// `./gradlew compileGametestKotlin` — just typecheck the harness
//
// The test mod carries no mixins of its own; it reaches Iustitia's pipeline
// through the public facade + SelfTestHooks. `eula = true` is required by Loom
// because the gametest runner can boot a dedicated server for server-side
// gametests; we only run client gametests, but the flag is global.
// -----------------------------------------------------------------------------
if (sc.current.parsed < "26.1") {
    fabricApi {
        configureTests {
            createSourceSet = true
            modId = "iustitia_testmod"
            enableGameTests = false
            enableClientGameTests = true
            eula = true
        }
    }

    // The gametest JVM runs headless-friendly and fails fast; the property arms the
    // SelfTestHooks recording tap inside the main mod (see dev.iustitia.selftest).
    //
    // Optional scenario narrowing (scripts/live_selftest.py passes these through):
    //   ./gradlew runClientGameTest -Pselftest.filter=reach      (name substring(s), comma-separated)
    //   ./gradlew runClientGameTest -Pselftest.pass=CHEAT        (LEGIT | CHEAT | REPLAY)
    //   ./gradlew runClientGameTest -Pselftest.source=Meteor     (reference-client provenance)
    //   ./gradlew runClientGameTest -Pselftest.tag=world         (combat | movement | …)
    //   ./gradlew runClientGameTest -Pselftest.shard=1/2         (shard I of N, by scenario name)
    // A filter that matches nothing FAILS the run, so a typo can never look like a pass.
    tasks.withType<JavaExec>().named("runClientGameTest") {
        jvmArgs("-Dfabric.selftest=1")
        // Iustitia observes packets by hooking the netty pipeline (ClientPlayNetworkHandlerMixin),
        // which is exactly the "interfacing with packets at a lower level" case the Fabric client
        // gametest API's network synchronizer cannot model. With the synchronizer active, full runs
        // intermittently die inside the framework's own phase machine: the server thread parks
        // forever on its postRunTasks semaphore during world creation (spawn prep never advances,
        // world load times out, every later scenario cascade-fails), and packet-heavy scenarios can
        // trip the synchronizer's 10s "Detected interfacing with packets at a lower level" watch
        // outright. Disabling synchronization is the framework's documented escape for this class of
        // mod. Our drives publish synthetic signals on the client thread, so scenario timing does not
        // depend on packet/tick alignment; the ≤1-tick packet latency this reintroduces is already
        // tolerated by the engine's defer queue and the scenarios' window margins.
        jvmArgs("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
        providers.gradleProperty("selftest.filter").orNull?.let { jvmArgs("-Diustitia.selftest.filter=$it") }
        providers.gradleProperty("selftest.pass").orNull?.let { jvmArgs("-Diustitia.selftest.pass=$it") }
        providers.gradleProperty("selftest.source").orNull?.let { jvmArgs("-Diustitia.selftest.source=$it") }
        providers.gradleProperty("selftest.tag").orNull?.let { jvmArgs("-Diustitia.selftest.tag=$it") }
        providers.gradleProperty("selftest.shard").orNull?.let { jvmArgs("-Diustitia.selftest.shard=$it") }
        // Arms the mod's verbose flag log so a run prints the sub-flag label + measured value for
        // every flag (the calibration loop's evidence). Off by default; the report is the summary.
        providers.gradleProperty("selftest.verbose").orNull?.let { jvmArgs("-Diustitia.selftest.verbose=$it") }
    }
}
