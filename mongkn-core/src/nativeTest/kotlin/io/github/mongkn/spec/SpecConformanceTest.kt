package io.github.mongkn.spec

import io.github.mongkn.MongoClient
import io.github.mongkn.bson.BsonArray
import io.github.mongkn.bson.BsonString
import io.github.mongkn.support.readJsonDocument
import io.github.mongkn.support.requiredPath
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Прогон официальных spec-тестов MongoDB (M-30).
 *
 * Файлы скачиваются задачей `:mongkn-core:fetchSpecTests` в `build/spec-tests` и **в репозиторий
 * не кладутся**: `mongodb/specifications` лицензирован под CC BY-NC-SA 3.0 (NonCommercial,
 * ShareAlike), а mongkn рассчитывает на публикацию. Использовать их для проверки можно,
 * распространять копию — вопрос лицензии.
 *
 * Первый прогон требует сети.
 */
class SpecConformanceTest {

    private val uri = "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000&socketTimeoutMS=10000"

    private var client: MongoClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        client = null
    }

    @Test
    fun `official CRUD spec tests pass`() = runTest(timeout = 120.seconds) {
        val directory = requiredPath(
            "MONGKN_SPEC_TESTS",
            "тест запущен в обход :mongkn-core:fetchSpecTests",
        )
        val manifest = readJsonDocument("$directory/manifest.json")
        val files = (manifest["files"] as? BsonArray)?.values
            .orEmpty()
            .filterIsInstance<BsonString>()
            .map { it.value }

        assertTrue(files.isNotEmpty(), "манифест пуст: $directory/manifest.json")

        val connection = MongoClient(uri).also { client = it }
        val runner = SpecTestRunner(uri, connection)

        for (name in files) {
            val path = "$directory/$name"
            runner.runFile(path, readJsonDocument(path))
        }

        val report = runner.report()
        println(report.render())
        report.executed.forEach { println("  ✓ $it") }

        // Порог, а не «хотя бы один»: без него молчаливое расширение списка пропусков
        // выглядело бы как зелёный прогон. Если сценариев стало меньше — надо разбираться,
        // а не понижать число.
        assertTrue(
            report.executed.size >= MINIMUM_EXECUTED,
            "выполнено всего ${report.executed.size} сценариев, ожидалось не меньше " +
                "$MINIMUM_EXECUTED:\n${report.render()}",
        )
    }

    private companion object {
        /** Замерено на первом зелёном прогоне; поднимать вместе с ростом поддержки. */
        const val MINIMUM_EXECUTED = 5
    }
}
