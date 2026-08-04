// Все плагины объявляются здесь с `apply false`: иначе подпроект, объявляющий версию сам,
// натыкается на «plugin is already on the classpath with an unknown version».
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "io.github.mongkn"
version = "0.1.0-SNAPSHOT"
