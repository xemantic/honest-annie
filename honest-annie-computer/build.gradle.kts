// The computers the intents of cogitations execute on, see ComputerProvider:
// the interface of the plugins providing them, e.g. executor processes of the host, Docker containers, or Fly.io Machines,
// each one starting computers of the types it offers, and opening REPLs on them, which execute the intents,
// and their aggregate, see AggregateComputerProvider, offering the types of many providers.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("annie.convention")
}

kotlin {

    jvm()
    // native, so that a harness compiled natively can use it, switched off for now
    //macosArm64()
    //linuxX64()
    //linuxArm64()

    sourceSets {

        commonMain {
            dependencies {
                api(project(":honest-annie-protocol"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.xemantic.kotlin.test)
            }
        }

    }

}
