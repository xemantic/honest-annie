import com.xemantic.ai.annie.buildlogic.annieJvmArgs
import com.xemantic.ai.annie.buildlogic.usePythonVenvOfTests

// The harness: the host unfolding cogitations, inferring by the cognizers of the providers' APIs,
// and storing and recording the cogitations, the intents being executed by the executor.
plugins {
    id("annie.convention")
    application
}

dependencies {
    api(project(":honest-annie-api"))
    // in-process for now, to be replaced by an executor process, communicating through its standard input and output
    implementation(project(":honest-annie-executor"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xemantic.kotlin.introspection)
    implementation(libs.anthropic.java)
    implementation(libs.openai.java)
    implementation(libs.xemantic.ai.file.magic)
    implementation(libs.xemantic.ai.money)
    implementation(libs.kotlin.logging)

    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xemantic.kotlin.test)
}

// the application, and its distributions, are named after the whole project, not this module
val appName = rootProject.name

application {
    applicationName = appName
    mainClass = "com.xemantic.ai.annie.host.MainKt"
    applicationDefaultJvmArgs = annieJvmArgs
}

distributions.named("main") {
    distributionBaseName = appName
}

tasks.test {
    usePythonVenvOfTests()
}

// Run from the build, the harness uses the virtual environment of the tests,
// rather than preparing one of its own in the user cache, see PythonVenv.
tasks.named<JavaExec>("run") {
    args = (findProperty("input") as String?)?.let { listOf(it) } ?: emptyList()
    dependsOn(":installPythonPackages")
    // the cogitations are recorded in the directory Gradle was run from, by default the root of the project
    workingDir = rootProject.layout.projectDirectory.asFile
    // set at execution: setting it at configuration snapshots the whole environment into the configuration cache,
    // and a reused cache entry would then run the harness without the variables exported since, e.g. ANTHROPIC_MODEL
    val virtualEnv = rootProject.layout.projectDirectory.dir(".venv").asFile.absolutePath
    doFirst { (this as JavaExec).environment("VIRTUAL_ENV", virtualEnv) }
}

// Fills the constitution templates with the API, cognition and harness sources, as resources:
// constitution.md, the default one, and constitutions/<family>.md, the ones of model families, see Constitution.kt.
val generateConstitution = tasks.register("generateConstitution") {
    group = "annie"
    description = "Generates the constitution resources from their templates and the API, cognition and harness sources"
    val default = layout.projectDirectory.file("src/main/constitution.md")
    val families = layout.projectDirectory.dir("src/main/constitutions")
    val apiSourceDir = rootProject.layout.projectDirectory.dir("honest-annie-api/src/main/kotlin")
    val sources = mapOf(
        "api" to apiSourceDir.file("Api.kt"),
        "cognition" to apiSourceDir.file("Cognition.kt"),
        "harness" to layout.projectDirectory.file("src/main/kotlin/Harness.kt")
    )
    val outputDir = layout.buildDirectory.dir("generated/constitution/resources")
    inputs.file(default)
    inputs.dir(families)
    inputs.files(sources.values)
    outputs.dir(outputDir)
    doLast {
        val licenseHeader = Regex("""\A\s*/\*.*?\*/\s*""", RegexOption.DOT_MATCHES_ALL)
        val output = outputDir.get().asFile
        output.deleteRecursively()
        val templates = mapOf("constitution.md" to default.asFile) +
                families.asFile.listFiles { file -> file.extension == "md" }!!.associateBy { "constitutions/${it.name}" }
        templates.forEach { (path, template) ->
            val constitution = sources.entries.fold(template.readText()) { text, (name, file) ->
                val placeholder = "{{$name}}"
                check(placeholder in text) { "No $placeholder in $template" }
                text.replace(placeholder, file.asFile.readText().replaceFirst(licenseHeader, "").trimEnd())
            }
            output.resolve(path).apply { parentFile.mkdirs() }.writeText(constitution)
        }
    }
}

sourceSets.main {
    resources.srcDir(generateConstitution)
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
    distributionBaseName = "$appName-runtime"
    contents {
        from(tasks.installDist)
        exclude("bin/*.bat")
        filesMatching("bin/$appName") {
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
