import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.power.assert)
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.xemantic.conventions)
    application
}

group = "com.xemantic.ai"

xemantic {
    description = "A recursive harness for cognizers acting through Kotlin scripts"
    inceptionYear = "2026"
    applyAllConventions()
}

val javaTarget = libs.versions.javaTarget.get()
val kotlinTarget = KotlinVersion.fromVersion(libs.versions.kotlinTarget.get())

kotlin {

    // set up according to https://jakewharton.com/gradle-toolchains-are-rarely-a-good-idea/
    compilerOptions {
        apiVersion = kotlinTarget
        languageVersion = kotlinTarget
        jvmTarget = JvmTarget.fromTarget(javaTarget)
        freeCompilerArgs.addAll(
            "-Xjdk-release=$javaTarget",
            "-Xcontext-sensitive-resolution"
        )
        extraWarnings = true
        progressiveMode = true
    }

}

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
}

// the fast JAR file system of the Kotlin compiler calls sun.misc.Unsafe, the option is unknown before JDK 23
val compilerJvmArgs = if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_23)) {
    listOf("--sun-misc-unsafe-memory-access=allow")
} else emptyList()

application {
    mainClass = "com.xemantic.ai.annie.host.MainKt"
    applicationDefaultJvmArgs = compilerJvmArgs
}

tasks.named<JavaExec>("run") {
    args = (findProperty("input") as String?)?.let { listOf(it) } ?: emptyList()
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xemantic.kotlin.introspection)
    implementation(libs.xemantic.kotlin.script)
    implementation(libs.anthropic.java)
    implementation(libs.openai.java)
    implementation(libs.kotlin.logging)

    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xemantic.kotlin.test)
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(libs.versions.asm.get())
        }
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(compilerJvmArgs)
}

// Fills the constitution template with the API and cognition sources, as the constitution.md resource,
// leaving the {{harness}} placeholder to the runtime, since the harness can differ in each invocation.
val generateConstitution = tasks.register("generateConstitution") {
    group = "annie"
    description = "Generates the constitution.md resource from its template and the API and cognition sources"
    val template = layout.projectDirectory.file("src/main/constitution.md")
    val sourceDir = layout.projectDirectory.dir("src/main/kotlin")
    val sources = mapOf(
        "api" to sourceDir.file("Api.kt"),
        "cognition" to sourceDir.file("Cognition.kt")
    )
    val outputDir = layout.buildDirectory.dir("generated/constitution/resources")
    inputs.file(template)
    inputs.files(sources.values)
    outputs.dir(outputDir)
    doLast {
        val licenseHeader = Regex("""\A\s*/\*.*?\*/\s*""", RegexOption.DOT_MATCHES_ALL)
        val constitution = sources.entries.fold(template.asFile.readText()) { text, (name, file) ->
            val placeholder = "{{$name}}"
            check(placeholder in text) { "No $placeholder in $template" }
            text.replace(placeholder, file.asFile.readText().replaceFirst(licenseHeader, "").trimEnd())
        }
        val output = outputDir.get().file("constitution.md").asFile
        output.parentFile.mkdirs()
        output.writeText(constitution)
    }
}

// Turns the compiled source of the default harness into its script: the source without its package,
// which is imported instead, followed by the call of the `unfold` function with the properties of the script.
// The source belongs to the package the script imports, an import the IDE would remove from the source as redundant.
val generateDefaultHarness = tasks.register("generateDefaultHarness") {
    group = "annie"
    description = "Generates the default harness script from DefaultHarness.kt as the harness.kts resource"
    val source = layout.projectDirectory.file("src/main/kotlin/DefaultHarness.kt")
    val outputDir = layout.buildDirectory.dir("generated/harness/resources")
    inputs.file(source)
    outputs.dir(outputDir)
    doLast {
        val lines = source.asFile.readLines()
        val packageLine = lines.indexOfFirst { it.startsWith("package ") }
        val firstImport = lines.indexOfFirst { it.startsWith("import ") }
        check(packageLine != -1 && firstImport > packageLine) { "No package followed by imports in $source" }
        val script = lines.subList(packageLine + 1, firstImport).dropWhile { it.isBlank() } +
                (lines[packageLine].replaceFirst("package ", "import ") + ".*") +
                lines.subList(firstImport, lines.size).dropLastWhile { it.isBlank() } +
                "" +
                "unfold(cogitation, cognizer, executor, agency, introspector)"
        val output = outputDir.get().file("harness.kts").asFile
        output.parentFile.mkdirs()
        output.writeText(script.joinToString("\n") + "\n")
    }
}

sourceSets.main {
    resources.srcDir(generateConstitution)
    resources.srcDir(generateDefaultHarness)
}

tasks.register<JavaExec>("listModels") {
    group = "annie"
    description = "Lists models exposed by the OpenAI-compatible endpoint at OPENAI_BASE_URL"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xemantic.ai.annie.cognizer.openai.ListModelsKt"
    args = (findProperty("prompt") as String?)?.let { listOf(it) } ?: emptyList()
}

powerAssert {
    functions = listOf(
        "com.xemantic.kotlin.test.assert",
        "com.xemantic.kotlin.test.have"
    )
}

versionCatalogUpdate {
    // preserve the manual, logically-grouped ordering of libs.versions.toml
    sortByKey = false
    keep {
        // kotlinTarget / javaTarget are plain version constants with no version.ref
        versions = setOf("kotlinTarget", "javaTarget", "asm")
        keepUnusedVersions = false
    }
}
