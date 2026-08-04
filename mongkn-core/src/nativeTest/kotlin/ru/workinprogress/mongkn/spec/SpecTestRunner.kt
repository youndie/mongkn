package ru.workinprogress.mongkn.spec

import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.MongoCollection
import ru.workinprogress.mongkn.MongoException
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDateTime
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonDouble
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonObjectId
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document
import kotlinx.coroutines.flow.toList

/**
 * Раннер официальных spec-тестов MongoDB в [unified test format](https://github.com/mongodb/specifications/blob/master/source/unified-test-format/unified-test-format.md).
 *
 * Это то, чем MongoDB определяет «драйвер реализован верно»: одни и те же JSON-сценарии гоняет
 * каждый официальный драйвер. В отличие от наших собственных тестов, здесь ожидания не мои.
 *
 * **Раннер намеренно частичный.** Поддержано ровно то, что умеет mongkn; всё остальное
 * пропускается с указанием причины, а счётчик пропусков печатается. Скрывать объём непокрытого
 * нельзя: раннер, который «прошёл» 6 файлов, выполнив два сценария, создаёт ложное ощущение
 * соответствия.
 *
 * Известные упрощения, каждое ослабляет строгость проверки:
 *
 * * лишние поля допускаются **только в корне** `expectResult` — там драйвер вправе вернуть
 *   больше, чем перечислено в сценарии. Во вложенных документах и в `outcome` сравнение строгое:
 *   раньше подмножеством сравнивалось всё, и лишнее поле во вложенном документе проходило
 *   незамеченным (M-35);
 * * из специальных операторов реализованы `$$unsetOrMatches` и `$$type` — те, что встречаются
 *   в выбранных файлах;
 * * `expectEvents` не поддерживается вовсе (нет APM), такие тесты пропускаются целиком;
 * * `runOnRequirements` не вычисляется — топологию и версию сервера мы не спрашиваем.
 */
class SpecTestRunner(
    private val uri: String,
    private val client: MongoClient,
    /** Версия сервера покомпонентно — для `runOnRequirements`. */
    private val version: List<Int>,
) {

    data class Report(
        val executed: MutableList<String> = mutableListOf(),
        val skipped: MutableList<Pair<String, String>> = mutableListOf(),
        val coveredOperations: MutableSet<String> = mutableSetOf(),
    ) {
        fun render(): String = buildString {
            appendLine("spec-тесты: выполнено ${executed.size}, пропущено ${skipped.size}")
            skipped.groupBy { it.second }.forEach { (reason, items) ->
                appendLine("  пропущено (${items.size}): $reason")
            }
            // Без этой строки отчёт вводит в заблуждение: «5 сценариев прошло» звучит как
            // покрытие, хотя половина наших операций не задета ни одним из них.
            val uncovered = SUPPORTED.keys - coveredOperations
            appendLine("  операции под spec-покрытием: ${coveredOperations.sorted()}")
            appendLine("  БЕЗ spec-покрытия: ${uncovered.sorted()}")
        }
    }

    private val report = Report()

    fun report(): Report = report

    suspend fun runFile(path: String, file: BsonDocument) {
        val fileName = path.substringAfterLast('/')
        val entities = collectEntities(file)
        if (entities == null) {
            skipAll(file, fileName, "в createEntities есть неподдержанная сущность")
            return
        }

        for (test in file.arrayOf("tests")) {
            val case = test as? BsonDocument ?: continue
            val name = "$fileName :: ${case.stringOf("description")}"

            val skipReason = when {
                "expectEvents" in case -> "expectEvents: APM не реализован"
                else -> unmetRequirements(file) ?: unmetRequirements(case) ?: unsupportedOperation(case)
            }
            if (skipReason != null) {
                report.skipped += name to skipReason
                continue
            }

            seedInitialData(file)
            runOperations(case, entities, name)
            verifyOutcome(case, name)
            report.executed += name
            for (entry in case.arrayOf("operations")) {
                (entry as? BsonDocument)?.let { report.coveredOperations += it.stringOf("name") }
            }
        }
    }

    // --- разбор описания -------------------------------------------------------------------

    /** id коллекции → (база, коллекция). `null`, если файл использует сущность, которой у нас нет. */
    private fun collectEntities(file: BsonDocument): Map<String, Pair<String, String>>? {
        val databases = mutableMapOf<String, String>()
        val collections = mutableMapOf<String, Pair<String, String>>()

        for (entry in file.arrayOf("createEntities")) {
            val entity = entry as? BsonDocument ?: return null
            val (kind, body) = entity.entries.firstOrNull() ?: return null
            val spec = body as? BsonDocument ?: return null
            when (kind) {
                "client" -> Unit // один клиент на прогон; настройки клиента не поддерживаем
                "database" -> databases[spec.stringOf("id")] = spec.stringOf("databaseName")
                "collection" -> {
                    val database = databases[spec.stringOf("database")] ?: return null
                    collections[spec.stringOf("id")] = database to spec.stringOf("collectionName")
                }
                else -> return null
            }
        }
        return collections
    }

    /**
     * Проверяет `runOnRequirements`: возвращает причину пропуска или `null`, если требования сняты.
     *
     * Умеет только версию сервера — этого хватает для выбранных файлов. Всё остальное
     * (топология, тип аутентификации, serverless) честно отказывается выполнять сценарий,
     * а не делает вид, что требование выполнено: молчаливое «подходит» превратило бы
     * непроверенное в зелёное.
     */
    private fun unmetRequirements(node: BsonDocument): String? {
        val requirements = node.arrayOf("runOnRequirements").filterIsInstance<BsonDocument>()
        if (requirements.isEmpty()) return null

        val reasons = requirements.map { requirement ->
            val unknown = requirement.keys - KNOWN_REQUIREMENTS
            when {
                unknown.isNotEmpty() -> "runOnRequirements: не умеем проверять ${unknown.sorted()}"
                !satisfies(requirement) -> "runOnRequirements: версия сервера не подходит"
                else -> null
            }
        }
        // Требования — список альтернатив: достаточно, чтобы подошла одна.
        return if (reasons.any { it == null }) null else reasons.filterNotNull().first()
    }

    private fun satisfies(requirement: BsonDocument): Boolean {
        (requirement["minServerVersion"] as? BsonString)?.let {
            if (compareVersions(version, parseVersion(it.value)) < 0) return false
        }
        (requirement["maxServerVersion"] as? BsonString)?.let {
            if (compareVersions(version, parseVersion(it.value)) > 0) return false
        }
        return true
    }

    private fun unsupportedOperation(case: BsonDocument): String? {
        for (entry in case.arrayOf("operations")) {
            val operation = entry as? BsonDocument ?: return "операция не документ"
            val name = operation.stringOf("name")
            val target = operation.stringOf("object")
            val allowed = SUPPORTED[name] ?: return "операция '$name' не реализована"
            if (!target.startsWith("collection")) return "объект '$target' не коллекция"

            // Молча проигнорировать незнакомый аргумент опаснее, чем пропустить тест: сценарий
            // с `ordered: false` прошёл бы «успешно», проверив совсем не то, что задумано.
            val arguments = (operation["arguments"] as? BsonDocument)?.keys.orEmpty()
            val extra = arguments - allowed
            if (extra.isNotEmpty()) return "аргументы не поддержаны: ${extra.sorted()}"
        }
        return null
    }

    // --- выполнение ------------------------------------------------------------------------

    private suspend fun seedInitialData(file: BsonDocument) {
        for (entry in file.arrayOf("initialData")) {
            val data = entry as? BsonDocument ?: continue
            val collection = client
                .getDatabase(data.stringOf("databaseName"))
                .getCollection(data.stringOf("collectionName"))

            // Своего drop у нас нет — чистим удалением. Для объёмов spec-тестов этого хватает.
            while (collection.countDocuments() > 0) {
                collection.deleteOne(BsonDocument())
            }
            val documents = data.arrayOf("documents").filterIsInstance<BsonDocument>()
            if (documents.isNotEmpty()) collection.insertMany(documents)
        }
    }

    private suspend fun runOperations(
        case: BsonDocument,
        entities: Map<String, Pair<String, String>>,
        name: String,
    ) {
        for (entry in case.arrayOf("operations")) {
            val operation = entry as? BsonDocument ?: continue
            val target = entities[operation.stringOf("object")]
                ?: error("$name: неизвестная сущность ${operation.stringOf("object")}")
            val collection = client.getDatabase(target.first).getCollection(target.second)
            val arguments = operation["arguments"] as? BsonDocument ?: BsonDocument()

            val expectsError = operation["expectError"] != null
            val actual = try {
                invoke(collection, operation.stringOf("name"), arguments)
            } catch (e: MongoException) {
                check(expectsError) { "$name: операция упала неожиданно: ${e.message}" }
                continue
            }
            check(!expectsError) { "$name: ожидалась ошибка, а операция прошла" }

            val expected = operation["expectResult"] ?: continue
            // Результат операции сравнивается «как корень»: драйвер вправе вернуть больше полей,
            // чем перечислено в сценарии (например upsertedCount там, где его не ждут).
            check(SpecMatcher.matches(expected, actual, root = true)) {
                "$name: результат не совпал\n  ожидалось: $expected\n  получено:  $actual"
            }
        }
    }

    private suspend fun invoke(
        collection: MongoCollection<Document>,
        name: String,
        arguments: BsonDocument,
    ): BsonValue = when (name) {
        "insertOne" -> BsonDocument(
            "insertedId" to collection.insertOne(arguments.documentOf("document")).insertedId
        )

        "insertMany" -> {
            val documents = arguments.arrayOf("documents").filterIsInstance<BsonDocument>()
            val result = collection.insertMany(documents, ordered = arguments.flagOf("ordered", default = true))
            // Официальный формат ждёт insertedIds документом «индекс → _id», а не списком.
            BsonDocument(
                "insertedIds" to BsonDocument(
                    result.insertedIds.mapIndexed { index, id -> index.toString() to id }
                )
            )
        }

        "updateOne" -> collection
            .updateOne(
                arguments.documentOf("filter"),
                arguments.documentOf("update"),
                upsert = arguments.flagOf("upsert", default = false),
            )
            .let {
                BsonDocument(
                    "matchedCount" to BsonInt32(it.matchedCount.toInt()),
                    "modifiedCount" to BsonInt32(it.modifiedCount.toInt()),
                    "upsertedCount" to BsonInt32(if (it.upsertedId == null) 0 else 1),
                ).let { base ->
                    // upsertedId сценарии ждут только когда апсерт случился.
                    if (it.upsertedId == null) base
                    else BsonDocument(base.entries + ("upsertedId" to it.upsertedId))
                }
            }

        "deleteOne" -> BsonDocument(
            "deletedCount" to BsonInt32(collection.deleteOne(arguments.documentOf("filter")).deletedCount.toInt())
        )

        "find" -> {
            var query = collection.find(arguments.documentOf("filter"))
            arguments.intOf("skip")?.let { query = query.skip(it) }
            arguments.intOf("limit")?.let { query = query.limit(it) }
            arguments.intOf("batchSize")?.let { query = query.batchSize(it) }
            (arguments["sort"] as? BsonDocument)?.let { query = query.sort(it) }
            BsonArray(query.toList())
        }

        "countDocuments" -> BsonInt64(collection.countDocuments(arguments.documentOf("filter")))

        else -> error("операция '$name' не поддержана — должна была отсеяться раньше")
    }

    private suspend fun verifyOutcome(case: BsonDocument, name: String) {
        for (entry in case.arrayOf("outcome")) {
            val expected = entry as? BsonDocument ?: continue
            val collection = client
                .getDatabase(expected.stringOf("databaseName"))
                .getCollection(expected.stringOf("collectionName"))

            val actual = collection.find().toList().sortedBy { it["_id"]?.toString() }
            val wanted = expected.arrayOf("documents")
                .filterIsInstance<BsonDocument>()
                .sortedBy { it["_id"]?.toString() }

            check(actual.size == wanted.size) {
                "$name: в коллекции ${expected.stringOf("collectionName")} ${actual.size} документов, ждали ${wanted.size}"
            }
            // Содержимое коллекции сверяется **строго**: лишнее поле в сохранённом документе
            // означает, что операция записала не то, и послаблений тут быть не должно.
            for ((index, document) in wanted.withIndex()) {
                check(SpecMatcher.matches(document, actual[index])) {
                    "$name: документ $index не совпал\n  ожидалось: $document\n  получено:  ${actual[index]}"
                }
            }
        }
    }

    // --- сравнение -------------------------------------------------------------------------

    private fun skipAll(file: BsonDocument, fileName: String, reason: String) {
        for (test in file.arrayOf("tests")) {
            val case = test as? BsonDocument ?: continue
            report.skipped += "$fileName :: ${case.stringOf("description")}" to reason
        }
    }

    private fun BsonDocument.arrayOf(key: String): List<BsonValue> =
        (this[key] as? BsonArray)?.values.orEmpty()

    private fun BsonDocument.stringOf(key: String): String =
        (this[key] as? BsonString)?.value ?: error("ожидалась строка в поле \"$key\": $this")

    private fun BsonDocument.flagOf(key: String, default: Boolean): Boolean =
        (this[key] as? BsonBoolean)?.value ?: default

    private fun BsonDocument.intOf(key: String): Int? = when (val value = this[key]) {
        is BsonInt32 -> value.value
        is BsonInt64 -> value.value.toInt()
        else -> null
    }

    private fun BsonDocument.documentOf(key: String): Document =
        this[key] as? BsonDocument ?: BsonDocument()

    private companion object {
        /** Требования, которые раннер умеет вычислять. Остальные — повод пропустить. */
        val KNOWN_REQUIREMENTS = setOf("minServerVersion", "maxServerVersion")

        /** Операция → аргументы, которые mongkn действительно учитывает. */
        val SUPPORTED: Map<String, Set<String>> = mapOf(
            "insertOne" to setOf("document"),
            "insertMany" to setOf("documents", "ordered"),
            "updateOne" to setOf("filter", "update", "upsert"),
            "deleteOne" to setOf("filter"),
            "find" to setOf("filter", "limit", "skip", "sort", "batchSize"),
            "countDocuments" to setOf("filter"),
        )
    }
}
