plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.coroutines.core)
}

/**
 * Дифференциальные тесты (M-28).
 *
 * Официальный драйвер живёт только на JVM, а `mongkn` — только на Native, поэтому в одном
 * процессе их не свести. Круг замыкается тремя фазами, каждая в своём процессе, вокруг одного
 * mongod:
 *
 * 1. `seedDiffReference` (здесь) — эталон пишет документ и выгружает его canonical extended JSON;
 * 2. нативный тест `MongoDifferentialTest` — mongkn читает то же самое и сверяет с фикстурой
 *    (**проверка нашего декодера**), затем пишет свою копию;
 * 3. `verifyDiffWritten` (здесь) — эталон читает написанное mongkn и сверяет с собой
 *    (**проверка нашего кодировщика**).
 *
 * Порядок задан зависимостями задач в `:mongkn-core`, а не соглашением.
 */
val diffUri: String = providers.gradleProperty("mongkn.diff.uri").orNull
    ?: providers.environmentVariable("MONGKN_TEST_HOST").map { "mongodb://$it" }.orNull
    ?: "mongodb://127.0.0.1:27017"

/** Файл фикстуры. Путь отдаётся нативной стороне переменной окружения — см. `:mongkn-core`. */
val diffFixture: Provider<RegularFile> = layout.buildDirectory.file("diff/reference.json")

val seedDiffReference by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Фаза A: официальный драйвер пишет эталонный документ и выгружает фикстуру"
    mainClass.set("io.github.mongkn.difftest.Seed")
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add { listOf(diffUri, diffFixture.get().asFile.absolutePath) }
    outputs.file(diffFixture)
    // Состояние в mongod задача не контролирует, поэтому её результат нельзя считать
    // актуальным по одному лишь наличию файла.
    outputs.upToDateWhen { false }
}

val verifyDiffWritten by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Фаза C: официальный драйвер сверяет с эталоном то, что записал mongkn"
    mainClass.set("io.github.mongkn.difftest.Verify")
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add { listOf(diffUri) }
    outputs.upToDateWhen { false }
}
