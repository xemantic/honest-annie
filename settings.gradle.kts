rootProject.name = "honest-annie"

pluginManagement {
    includeBuild("build-logic")
}

include(
    "honest-annie-api",
    "honest-annie-executor",
    "honest-annie-harness"
)
