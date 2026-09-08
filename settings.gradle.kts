rootProject.name = "mongkn"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // One group, and it is the only one there can be. The portfolio's move to
                // `io.github.youndie` is finished: nothing this build resolves is under
                // `ru.workinprogress` any more, and a filter naming a group the server is never asked
                // about reads as a dependency that is still there.
                includeGroupByRegex("io\\.github\\.youndie.*")
            }
        }
    }
}

plugins {
    // The repositories with their content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use.
    id("io.github.youndie.sborka.settings") version "0.3.0.41"
}

include(":mongkn-core")

// Дифференциальные тесты: эталоном служит официальный JVM-драйвер, поэтому модуль на JVM.
// Он переживает удаление генератора (M-33) — это единственное, ради чего официальный драйвер
// остаётся в проекте после решения Р9.
include(":mongkn-difftest")

// Эргономика отдельным артефактом, а не частью ядра: решение Р7. К этой же схеме пришёл
// вендор, вынеся mongodb-driver-kotlin-extensions из основного драйвера.
include(":mongkn-extensions")
