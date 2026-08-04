import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/**
 * Публикация в приватный Reposilite (M-18).
 *
 * Координаты и способ передачи кредов повторяют соглашение соседних проектов: URL
 * `https://maven.internal/private`, логин и пароль — из Gradle-свойств
 * `REPOSILITE_USER` / `REPOSILITE_SECRET` либо из одноимённых переменных окружения.
 * Ни то, ни другое в репозиторий не попадает.
 *
 * Скрипт применяется только к публикуемым модулям: `:mongkn-difftest` — тестовая оснастка,
 * наружу не выкладывается.
 *
 * Блок `plugins { }` здесь недоступен — это применяемый скрипт, а не build-файл, поэтому
 * плагин подключается `apply`, а расширение настраивается через `configure`.
 */
apply(plugin = "maven-publish")

configure<PublishingExtension> {
    repositories {
        maven {
            name = "mavenPrivate"
            url = uri("https://maven.internal/private")
            credentials {
                username = providers.gradleProperty("REPOSILITE_USER").orNull
                    ?: System.getenv("REPOSILITE_USER")
                password = providers.gradleProperty("REPOSILITE_SECRET").orNull
                    ?: System.getenv("REPOSILITE_SECRET")
            }
        }
    }

    // KMP-плагин создаёт публикации сам — по одной на таргет плюс `kotlinMultiplatform`.
    // Здесь только метаданные POM; создавать publication руками не нужно и вредно.
    publications.withType(MavenPublication::class.java).configureEach {
        pom {
            name.set("mongkn")
            description.set(
                "MongoDB для Kotlin/Native: обвязка над официальным C-драйвером с API, " +
                    "форма которого снята с mongodb-driver-kotlin-coroutine"
            )
            // Секции licenses намеренно нет: репозиторий приватный, лицензия не выбрана.
        }
    }
}
