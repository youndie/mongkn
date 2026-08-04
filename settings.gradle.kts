rootProject.name = "mongkn"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":mongkn-core")

// Дифференциальные тесты: эталоном служит официальный JVM-драйвер, поэтому модуль на JVM.
// Он переживает удаление генератора (M-33) — это единственное, ради чего официальный драйвер
// остаётся в проекте после решения Р9.
include(":mongkn-difftest")
