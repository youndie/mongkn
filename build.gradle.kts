// Все плагины объявляются здесь с `apply false`: иначе подпроект, объявляющий версию сам,
// натыкается на «plugin is already on the classpath with an unknown version».
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Координаты задаются всем проектам, а не только корню: подпроект по умолчанию получает
// группой имя корневого проекта, и артефакты уехали бы в `mongkn`, а не в `ru.workinprogress.mongkn`.
allprojects {
    group = "ru.workinprogress.mongkn"
    version = providers.gradleProperty("VERSION").getOrElse("0.1.0-SNAPSHOT")
}
