plugins {
    id("spring-boot-service")
}

dependencies {
    api(libs.contract.common)
    api(libs.swagger.annotations.jakarta)
    implementation(libs.jwt.api)
    runtimeOnly(libs.jwt.impl)
    runtimeOnly(libs.jwt.jackson)
}
