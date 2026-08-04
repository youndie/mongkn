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

// Эргономика отдельным артефактом, а не частью ядра: решение Р7. К этой же схеме пришёл
// вендор, вынеся mongodb-driver-kotlin-extensions из основного драйвера.
include(":mongkn-extensions")
