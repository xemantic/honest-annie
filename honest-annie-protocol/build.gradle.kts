// The protocol between the harness and the executor, see Protocol.kt, and the ScriptExecutor both sides implement,
// applying the edits of intents, see Edits.kt, and reading the files of Agency.read, see Lines.kt: what they share beyond the API pasted into the constitutions,
// multiplatform, so that a harness of another platform than the JVM can use it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("annie.convention")
}

kotlin {

    jvm()
    // native, so that a harness compiled natively can use it, the executor staying on the JVM,
    // switched off for now
    //macosArm64()
    //linuxX64()
    //linuxArm64()

    sourceSets {

        commonMain {
            dependencies {
                api(project(":honest-annie-api"))
                // the files of Agency.read and of the edits
                implementation(libs.kotlinx.io.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
            }
        }

    }

}
