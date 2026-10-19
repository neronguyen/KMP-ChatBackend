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
    useJUnitPlatform()
}
