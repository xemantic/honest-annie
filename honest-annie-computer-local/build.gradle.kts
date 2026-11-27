import com.xemantic.annie.buildlogic.usePythonVenvOfTests

// The computers of the host itself, without isolation: each one an executor process of its own, see LocalComputerProvider,
// or, for the tests, the process of the host, see InProcessComputerProvider.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("annie.convention")
}

dependencies {
    api(project(":honest-annie-computer"))
    // the REPLs of the computers in the process of the host, and the main of the executor processes, started of the same classpath
    implementation(project(":honest-annie-executor"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xemantic.kotlin.test)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    usePythonVenvOfTests()
}
