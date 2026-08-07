// Все плагины объявляются здесь с `apply false`: иначе подпроект, объявляющий версию сам,
// натыкается на «plugin is already on the classpath with an unknown version».
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

// Координаты задаются всем проектам, а не только корню: подпроект по умолчанию получает
// группой имя корневого проекта, и артефакты уехали бы в `mongkn`, а не в `io.github.youndie.mongkn`.
/*
 * ktlint подключается всем подпроектам разом.
 *
 * Плагин — лишь запускалка: сам форматтер это отдельный CLI, и его версия задаётся явно
 * (`ktlint = "1.8.0"` в каталоге). Без явного указания плагин возьмёт свою умолчательную,
 * и правила поедут при обновлении плагина, а не когда мы этого захотим.
 *
 * Задача `ktlintCheck` плагин сам вешает на `check`, поэтому в гейт она попадает через
 * обычный `./gradlew build` — отдельной строки в CI не нужно.
 */
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
    }
}

allprojects {
    // `io.github.<логин>` — координаты, право на которые доказывается владением аккаунтом
    // GitHub. Свой домен потребовал бы отдельного подтверждения владения им.
    group = "io.github.youndie.mongkn"
    version = providers.gradleProperty("VERSION").getOrElse("0.1.0-SNAPSHOT")
}
