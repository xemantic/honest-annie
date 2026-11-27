rootProject.name = "honest-annie"

pluginManagement {
    includeBuild("build-logic")
}

include(
    "honest-annie-api",
    "honest-annie-protocol",
    "honest-annie-computer",
    "honest-annie-computer-local",
    "honest-annie-executor",
    "honest-annie-harness"
)
