import com.xemantic.ai.annie.buildlogic.usePythonVenvOfTests

// The executor of the scripts of intents: Kotlin script sessions, the embedded Python of Jep, and processes.
plugins {
    id("annie.convention")
}

dependencies {
    api(project(":honest-annie-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xemantic.kotlin.introspection)
    implementation(libs.xemantic.kotlin.script)
    implementation(libs.xemantic.kotlin.script.python)
    // not used by the executor itself, but by the scripts of cognizers, see the constitution
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xemantic.kotlin.test)
}

tasks.test {
    usePythonVenvOfTests()
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

sourceSets.main {
    resources.srcDir(generatePythonProperties)
}
