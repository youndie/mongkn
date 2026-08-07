import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/*
 * Публикация в Maven-репозиторий (M-18).
 *
 * Адрес, логин и пароль берутся из Gradle-свойств `MONGKN_REPO_URL` / `MONGKN_REPO_USER` /
 * `MONGKN_REPO_SECRET` либо из одноимённых переменных окружения. В репозиторий не попадает
 * ничего из этого: у каждого, кто собирает mongkn, назначение своё, а без настройки остаётся
 * `publishToMavenLocal`.
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
        /*
         * Адрес репозитория берётся из настройки, а не записан здесь.
         *
         * Причина не в секретности — репозиторий и так за паролем, — а в том, что это чужая
         * инфраструктура: у каждого, кто соберёт mongkn, она своя. Задаётся свойством
         * `MONGKN_REPO_URL` (в `~/.gradle/gradle.properties`) или одноимённой переменной
         * окружения; без него блок просто не заводится, и остаётся `publishToMavenLocal`.
         */
        val repositoryUrl =
            providers.gradleProperty("MONGKN_REPO_URL").orNull
                ?: System.getenv("MONGKN_REPO_URL")

        if (repositoryUrl != null) {
            maven {
                name = "mongknRepo"
                url = uri(repositoryUrl)
                credentials {
                    username = providers.gradleProperty("MONGKN_REPO_USER").orNull
                        ?: System.getenv("MONGKN_REPO_USER")
                    password = providers.gradleProperty("MONGKN_REPO_SECRET").orNull
                        ?: System.getenv("MONGKN_REPO_SECRET")
                }
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
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
        }
    }
}
