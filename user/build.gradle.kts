plugins {
    id("spring-boot-service")
}

dependencies {
    implementation(projects.common)
    implementation(libs.contract.auth)

    implementation(libs.redisson.spring.boot.starter)
}
