/*
 * Загрузка официальных spec-тестов MongoDB (M-30).
 *
 * **Файлы намеренно не вендорятся в репозиторий.** `mongodb/specifications` лицензирован под
 * [CC BY-NC-SA 3.0 US](https://creativecommons.org/licenses/by-nc-sa/3.0/us/) — NonCommercial
 * и ShareAlike, — а mongkn рассчитывает на публикацию (M-18). Использовать эти файлы для
 * проверки соответствия можно, класть их копию в свой дистрибутив — уже вопрос лицензии.
 * Поэтому они скачиваются в `build/`, который в `.gitignore`.
 *
 * Побочное следствие: первый прогон требует сети. Дальше файлы лежат в `build/` и переиспользуются;
 * `./gradlew clean` их сотрёт.
 */

/** Файлы выбраны по покрытию: ровно те операции, которые mongkn умеет. */
val specTestFiles =
    listOf(
        "deleteOne.json",
        "insertOne.json",
        "insertMany.json",
        "updateOne.json",
        "find.json",
        "estimatedDocumentCount.json",
        // Веха M9: официальные сценарии для операций, добавленных по образцу существующих.
        "deleteMany.json",
        "updateMany.json",
        "replaceOne.json",
        "findOneAndUpdate.json",
        "findOneAndDelete.json",
        "findOneAndReplace.json",
        "distinct.json",
        // Веха M12.
        "aggregate.json",
        "bulkWrite.json",
    )

val specTestsDir: Provider<Directory> = layout.buildDirectory.dir("spec-tests")

val fetchSpecTests by tasks.registering {
    group = "verification"
    description = "Скачивает CRUD spec-тесты MongoDB в build/spec-tests (в репозиторий не кладутся)"

    val base = "https://raw.githubusercontent.com/mongodb/specifications/master/source/crud/tests/unified"
    val target = specTestsDir
    val files = specTestFiles
    outputs.dir(target)

    doLast {
        val dir = target.get().asFile
        dir.mkdirs()
        val fetched = mutableListOf<String>()
        for (name in files) {
            val file = dir.resolve(name)
            if (!file.exists()) {
                logger.lifecycle("spec-tests: качаю $name")
                file.writeBytes(uri("$base/$name").toURL().readBytes())
            }
            fetched += name
        }
        // Манифест нужен, чтобы нативный тест не читал каталог: перечень файлов он берёт
        // из JSON той же libbson, которой парсит и сами тесты.
        dir.resolve("manifest.json").writeText(
            fetched.joinToString(prefix = "{\"files\": [", postfix = "]}") { "\"$it\"" },
        )
    }
}
