plugins {
    id("spring-boot-service")
}

dependencies {
    implementation(projects.common)
    implementation(libs.contract.chat)

    implementation(libs.spring.boot.starter.websocket)
}
