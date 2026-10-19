pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/neronguyen/KMP-ChatContract")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "NeroChatBackendSpringBoot"

val contractRepoDir = file("../KMP-NeroChatContract")
val useLocalContract = (providers.gradleProperty("useLocalContract").orNull != "false") && contractRepoDir.exists()
if (useLocalContract) {
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
            substitute(module("io.github.neronguyenvn.nerochat.contract:validation-core")).using(
                project(":validation-core")
            )
            substitute(module("io.github.neronguyenvn.nerochat.contract:validation-auth")).using(
                project(":validation-auth")
            )
            substitute(module("io.github.neronguyenvn.nerochat.contract:validation-chat")).using(
                project(":validation-chat")
            )
        }
    }
}

include("app")
include("common")
include("chat")
include("user")
include("notification")
