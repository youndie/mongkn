package io.github.mongkn.spec

/**
 * Модуль существует ради одного: дать KSP место, где на classpath лежит официальный
 * JVM-драйвер, а значит его сигнатуры резолвятся.
 *
 * Сам этот файл ничего не делает — процессору нужен непустой source set, чтобы задача
 * `kspKotlin` вообще запустилась. Результат работы процессора уходит в
 * `build/generated/ksp/main/kotlin` и оттуда подхватывается `:mongkn-core` как srcDir.
 */
internal object ApiSpec
