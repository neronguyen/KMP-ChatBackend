pluginManagement {
    includeBuild("build-logic")
}

val githubActor = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
val githubToken = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
val contractRepoDir = file("../KMP-ChatContract")
val useLocalContract = (providers.gradleProperty("useLocalContract").orNull != "false") && contractRepoDir.exists()

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        if (githubActor != null && githubToken != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/neronguyen/KMP-ChatContract")
                credentials {
                    username = githubActor
                    password = githubToken
                }
            }
        } else if (!useLocalContract) {
            logger.warn("WARNING: GITHUB_ACTOR or GITHUB_TOKEN is not set. GitHubPackages repository could not be configured.")
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "KMP-ChatBackend"

if (useLocalContract) {
    logger.lifecycle("Notice: Using local contract repository substitution from: {}", contractRepoDir.canonicalPath)
    includeBuild(contractRepoDir) {
        dependencySubstitution {
            substitute(module("io.github.neronguyenvn.nerochat.contract:common")).using(
                project(":contract:common")
            )
            substitute(module("io.github.neronguyenvn.nerochat.contract:auth")).using(
                project(":contract:auth")
            )
            substitute(module("io.github.neronguyenvn.nerochat.contract:chat")).using(
                project(":contract:chat")
            )
            substitute(module("io.github.neronguyenvn.nerochat.validation:common")).using(
                project(":validation:common")
            )
            substitute(module("io.github.neronguyenvn.nerochat.validation:auth")).using(
                project(":validation:auth")
            )
            substitute(module("io.github.neronguyenvn.nerochat.validation:chat")).using(
                project(":validation:chat")
            )
        }
    }
}

include("app")
include("common")
include("chat")
include("user")
include("notification")
