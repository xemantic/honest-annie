plugins {
    // loaded once, by the root, as the Kotlin Gradle plugin fails on one loaded by several modules
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.xemantic.conventions)
}

group = "com.xemantic.annie"

xemantic {
    description = "A recursive harness for cognizers acting through Kotlin scripts"
    inceptionYear = "2026"
    applyAllConventions()
}

allprojects {
    group = "com.xemantic.annie"
}

// The Python virtual environment of the tests, with only the jep package matching the org.ninia:jep library,
// like a clean environment of a user, the tests declaring the Python packages they use as dependencies,
// see usePythonVenvOfTests of build-logic.
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

tasks.register<Exec>("installPythonPackages") {
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

versionCatalogUpdate {
    // preserve the manual, logically-grouped ordering of libs.versions.toml
    sortByKey = false
    keep {
        // kotlinTarget / javaTarget are plain version constants with no version.ref, jep the one of the Python package
        versions = setOf("kotlinTarget", "javaTarget", "asm", "jep")
        keepUnusedVersions = false
    }
}
