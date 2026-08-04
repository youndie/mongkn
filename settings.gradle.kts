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

// Генерация API живёт на JVM: в нативной компиляции jar официального драйвера на classpath
// не попадает, и KSP его не увидит (ресёрч §1.5, решение Р5).
// :mongkn-codegen — сам процессор; :mongkn-api-spec — модуль, на котором он запускается
// и куда складывает исходники для :mongkn-core.
include(":mongkn-codegen")
include(":mongkn-api-spec")

// Дифференциальные тесты: эталоном служит официальный JVM-драйвер, поэтому модуль на JVM.
// Он переживает удаление генератора (M-33) — это единственное, ради чего официальный драйвер
// остаётся в проекте после решения Р9.
include(":mongkn-difftest")
