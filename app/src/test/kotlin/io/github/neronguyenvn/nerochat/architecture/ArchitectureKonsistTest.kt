package io.github.neronguyenvn.nerochat.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureKonsistTest {

    @Test
    fun `peer domain modules do not depend on each other`() {
        Konsist
            .scopeFromProduction()
            .assertArchitecture {
                val user = Layer("User", "io.github.neronguyenvn.nerochat.user..")
                val chat = Layer("Chat", "io.github.neronguyenvn.nerochat.chat..")
                val notification = Layer("Notification", "io.github.neronguyenvn.nerochat.notification..")

                user.dependsOnNothing()
                chat.dependsOnNothing()
                notification.dependsOnNothing()
            }
    }

    @Test
    fun `service classes do not import spring web packages`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withPackage("..service..")
            .assertTrue { clazz ->
                clazz.containingFile.imports.none { import ->
                    import.name.startsWith("org.springframework.web")
                }
            }
    }

    @Test
    fun `service classes do not return wire contract DTOs`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withPackage("..service..")
            .flatMap { it.functions() }
            .assertTrue { function ->
                val returnTypeName = function.returnType?.name ?: return@assertTrue true
                !returnTypeName.contains("Dto")
            }
    }

    @Test
    fun `app module does not import domain exceptions from domain modules`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { it.path.contains("/app/src/main/") }
            .assertTrue { file ->
                file.imports.none { import ->
                    import.name.matches(Regex("io\\.github\\.neronguyenvn\\.nerochat\\.(user|chat|notification)\\.domain\\.exception\\..*"))
                }
            }
    }
}
