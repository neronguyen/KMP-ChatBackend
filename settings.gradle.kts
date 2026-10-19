pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "NeroChatBackendSpringBoot"

val contractRepoDir = file("../KMP-NeroChatContract")
if (contractRepoDir.exists()) {
    includeBuild(contractRepoDir) {
        dependencySubstitution {
            substitute(module("io.github.neronguyenvn.nerochat.contract:contract-core")).using(
                project(":contract-core")
            )
            substitute(module("io.github.neronguyenvn.nerochat.contract:contract-auth")).using(
                project(":contract-auth")
            )
            substitute(module("io.github.neronguyenvn.nerochat.contract:contract-chat")).using(
                project(":contract-chat")
            )
        }
    }
}

include("app")
include("common")
include("chat")
include("user")
include("notification")
