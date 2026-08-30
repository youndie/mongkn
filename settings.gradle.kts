rootProject.name = "mongkn"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    // The repositories with their content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use.
    id("ru.workinprogress.sborka.settings") version "0.1.0.13"
}

include(":mongkn-core")

// Дифференциальные тесты: эталоном служит официальный JVM-драйвер, поэтому модуль на JVM.
// Он переживает удаление генератора (M-33) — это единственное, ради чего официальный драйвер
// остаётся в проекте после решения Р9.
include(":mongkn-difftest")

// Эргономика отдельным артефактом, а не частью ядра: решение Р7. К этой же схеме пришёл
// вендор, вынеся mongodb-driver-kotlin-extensions из основного драйвера.
include(":mongkn-extensions")
