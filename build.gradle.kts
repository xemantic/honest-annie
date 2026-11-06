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

// Jep, embedding CPython for agency.python, loads its native library with System.load, restricted since JDK 24
val runtimeJvmArgs = compilerJvmArgs + "--enable-native-access=ALL-UNNAMED"

application {
    mainClass = "com.xemantic.ai.annie.host.MainKt"
    applicationDefaultJvmArgs = runtimeJvmArgs
}


repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xemantic.kotlin.introspection)
    implementation(libs.xemantic.kotlin.script)
    implementation(libs.xemantic.kotlin.script.python)
    // not used by the harness itself, but by the scripts of cognizers, see the constitution
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.anthropic.java)
    implementation(libs.openai.java)
    implementation(libs.xemantic.ai.file.magic)
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

// The Python virtual environment of the tests, with only the jep package matching the org.ninia:jep library,
// like a clean environment of a user, the tests declaring the Python packages they use as dependencies.
val pythonVenv = layout.projectDirectory.dir(".venv")
val venvPython = pythonVenv.file("bin/python")

val createPythonVenv = tasks.register<Exec>("createPythonVenv") {
    description = "Creates the Python virtual environment of the tests"
    // a local value, as the configuration cache cannot serialize a reference to the build script
    val python = venvPython.asFile
    outputs.file(python)
    onlyIf { !python.exists() }
    commandLine("python3", "-m", "venv", pythonVenv.asFile.absolutePath)
}

val installPythonPackages = tasks.register<Exec>("installPythonPackages") {
    description = "Installs jep into the virtual environment of the tests"
    dependsOn(createPythonVenv)
    val packages = listOf("jep==${libs.versions.jep.get()}")
    inputs.property("packages", packages)
    // in the virtual environment, so that the packages are installed again when it is recreated
    val marker = pythonVenv.file("installed-packages.txt")
    outputs.file(marker)
    commandLine(listOf(venvPython.asFile.absolutePath, "-m", "pip", "install", "--quiet", "--disable-pip-version-check") + packages)
    doLast {
        marker.asFile.writeText(packages.joinToString("\n"))
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(runtimeJvmArgs)
    inputs.files(installPythonPackages)
    environment("VIRTUAL_ENV", pythonVenv.asFile.absolutePath)
    // the dependencies declared by the tests are installed from scratch on every run, not into the user cache
    val dependencyDirectory = layout.buildDirectory.dir("python-dependencies").get().asFile
    systemProperty("xemantic.kotlin.script.python.dependencyDirectory", dependencyDirectory.absolutePath)
    doFirst {
        dependencyDirectory.deleteRecursively()
    }
}

// Run from the build, the harness uses the virtual environment of the tests,
// rather than preparing one of its own in the user cache, see PythonVenv.
tasks.named<JavaExec>("run") {
    args = (findProperty("input") as String?)?.let { listOf(it) } ?: emptyList()
    inputs.files(installPythonPackages)
    // set at execution: setting it at configuration snapshots the whole environment into the configuration cache,
    // and a reused cache entry would then run the harness without the variables exported since, e.g. ANTHROPIC_MODEL
    val virtualEnv = pythonVenv.asFile.absolutePath
    doFirst { (this as JavaExec).environment("VIRTUAL_ENV", virtualEnv) }
}

// The version of the jep package matching the org.ninia:jep library, installed by PythonVenv at runtime.
val generatePythonProperties = tasks.register("generatePythonProperties") {
    group = "annie"
    description = "Generates the python.properties resource with the version of jep"
    val jepVersion = libs.versions.jep.get()
    val outputDir = layout.buildDirectory.dir("generated/python/resources")
    inputs.property("jepVersion", jepVersion)
    outputs.dir(outputDir)
    doLast {
        val output = outputDir.get().file("python.properties").asFile
        output.parentFile.mkdirs()
        output.writeText("jep.version=$jepVersion\n")
    }
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
// which is imported instead, followed by the call of the suspending `unfold` function with the properties of the script.
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
                "await { unfold(cognizer, executor, agency) }"
        val output = outputDir.get().file("harness.kts").asFile
        output.parentFile.mkdirs()
        output.writeText(script.joinToString("\n") + "\n")
    }
}

sourceSets.main {
    resources.srcDir(generateConstitution)
    resources.srcDir(generateDefaultHarness)
    resources.srcDir(generatePythonProperties)
}

// The Java runtime of the JDK running the build, linked with the modules the scripts of cognizers can use:
// all of Java SE, not only the ones the harness depends on, plus the JDK modules the Kotlin compiler relies on.
val runtimeImage = tasks.register<Exec>("runtimeImage") {
    group = "annie"
    description = "Links the Java runtime bundled with the runtime distribution"
    val javaHome = System.getProperty("java.home")
    val modules = listOf(
        "java.se",
        "jdk.compiler",
        "jdk.unsupported",
        "jdk.zipfs",
        "jdk.charsets",
        "jdk.localedata",
        "jdk.crypto.cryptoki",
        "jdk.httpserver",
        "jdk.management",
        "jdk.naming.dns",
        "jdk.net",
        "jdk.security.auth"
    )
    val outputDir = layout.buildDirectory.dir("runtime-image")
    inputs.property("javaHome", javaHome)
    inputs.property("modules", modules)
    outputs.dir(outputDir)
    executable = "$javaHome/bin/jlink"
    args(
        "--add-modules", modules.joinToString(","),
        "--strip-debug",
        "--no-man-pages",
        "--no-header-files",
        "--compress", "zip-6",
        "--output", outputDir.get().asFile.path
    )
    // jlink refuses to write into an existing directory
    doFirst { outputDir.get().asFile.deleteRecursively() }
}

// The distribution carrying its own Java runtime, which its start script uses instead of JAVA_HOME.
distributions.register("runtime") {
    contents {
        from(tasks.installDist)
        exclude("bin/*.bat")
        filesMatching("bin/${project.name}") {
            filter { line ->
                if (line.startsWith("if [ -n \"\$JAVA_HOME\" ] ; then")) "JAVA_HOME=\$APP_HOME/runtime\n$line" else line
            }
        }
        into("runtime") {
            from(runtimeImage)
            // jlink makes the legal notices read-only, which would fail installing the distribution again
            filesMatching("**/legal/**") { permissions { user { write = true } } }
        }
    }
}

// The runtime is native, so its archive is named after the platform of the build, e.g. linux-x64 or macos-arm64.
tasks.named<Tar>("runtimeDistTar") {
    val os = System.getProperty("os.name").lowercase().let { if ("mac" in it) "macos" else it.substringBefore(' ') }
    val arch = System.getProperty("os.arch").let { if (it == "aarch64") "arm64" else if (it == "amd64" || it == "x86_64") "x64" else it }
    archiveClassifier = "$os-$arch"
    archiveExtension = "tar.gz"
    compression = Compression.GZIP
    // archives have fixed permissions by default, which would make the launcher and the runtime non-executable
    useFileSystemPermissions()
}

tasks.named("runtimeDistZip") {
    enabled = false
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
        // kotlinTarget / javaTarget are plain version constants with no version.ref, jep the one of the Python package
        versions = setOf("kotlinTarget", "javaTarget", "asm", "jep")
        keepUnusedVersions = false
    }
}
