plugins {
    id("spring-boot-service")
}

dependencies {
    api(libs.contract.core)
    implementation(libs.jwt.api)
    runtimeOnly(libs.jwt.impl)
    runtimeOnly(libs.jwt.jackson)
}
