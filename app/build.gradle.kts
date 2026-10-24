plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(projects.common)
    implementation(projects.user)
    implementation(projects.chat)
    implementation(projects.notification)

    implementation(libs.contract.core)
    implementation(libs.contract.auth)
    implementation(libs.contract.chat)

    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.kotlinx.serialization.json)

    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    implementation(libs.redisson.spring.boot.starter)
    implementation(libs.redisson.spring.cache)

    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("export")
    }
    systemProperty("project.rootDir", rootProject.projectDir.absolutePath)
}

tasks.register<Test>("exportApiDocs") {
    description = "Exports OpenAPI specification and Postman collection to the docs directory."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("export")
    }
    systemProperty("project.rootDir", rootProject.projectDir.absolutePath)
}
