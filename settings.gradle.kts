pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Android-CAD"

include(":core:lang")
include(":core:geometry")
include(":core:json")
include(":core:text")
include(":core:viewport")
include(":core:project")
include(":core:brep")
include(":core:sketch")
include(":core:sketchedit")
include(":core:model")
include(":app")
