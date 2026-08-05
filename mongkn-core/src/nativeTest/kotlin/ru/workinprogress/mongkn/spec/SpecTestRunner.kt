package ru.workinprogress.mongkn.spec

import kotlinx.coroutines.flow.toList
import ru.workinprogress.mongkn.CommandStartedEvent
import ru.workinprogress.mongkn.DeleteManyModel
import ru.workinprogress.mongkn.DeleteOneModel
import ru.workinprogress.mongkn.InsertOneModel
import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.MongoCollection
import ru.workinprogress.mongkn.MongoException
import ru.workinprogress.mongkn.ReplaceOneModel
import ru.workinprogress.mongkn.ReturnDocument
import ru.workinprogress.mongkn.UpdateManyModel
import ru.workinprogress.mongkn.UpdateOneModel
import ru.workinprogress.mongkn.UpdateResult
import ru.workinprogress.mongkn.WriteModel
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
 * * `expectEvents` проверяется только для `commandStartedEvent`; сценарий с другими типами
 *   событий или с неизвестным нам оператором сопоставления **пропускается**, а не проходит;
 * * `runOnRequirements` умеет только версию сервера; топологию не спрашиваем и такие сценарии
 *   честно пропускаем.
 */
internal class SpecTestRunner(
    private val uri: String,
    private val client: MongoClient,
    /** Записывает команды клиента — источник правды для `expectEvents`. */
    private val recorder: SpecEventRecorder,
    /** Версия сервера покомпонентно — для `runOnRequirements`. */
    private val version: List<Int>,
) {
    data class Report(
        val executed: MutableList<String> = mutableListOf(),
        val skipped: MutableList<Pair<String, String>> = mutableListOf(),
        val coveredOperations: MutableSet<String> = mutableSetOf(),
    ) {
        fun render(): String =
            buildString {
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

    suspend fun runFile(
        path: String,
        file: BsonDocument,
    ) {
        val fileName = path.substringAfterLast('/')
        val entities = collectEntities(file)
        if (entities == null) {
            skipAll(file, fileName, "в createEntities есть неподдержанная сущность")
            return
        }

        for (test in file.arrayOf("tests")) {
            val case = test as? BsonDocument ?: continue
            val name = "$fileName :: ${case.stringOf("description")}"

            val skipReason =
                unmetRequirements(file)
                    ?: unmetRequirements(case)
                    ?: unsupportedOperation(case)
                    ?: unsupportedEvents(case)
            if (skipReason != null) {
                report.skipped += name to skipReason
                continue
            }

            seedInitialData(file)
            // Чистится после засева: события подготовки к сценарию не относятся.
            recorder.clear()
            runOperations(case, entities, name)
            val observed = recorder.started()
            verifyOutcome(case, name)
            verifyEvents(case, observed, name)
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
                "client" -> {
                    Unit
                }

                // один клиент на прогон; настройки клиента не поддерживаем
                "database" -> {
                    databases[spec.stringOf("id")] = spec.stringOf("databaseName")
                }

                "collection" -> {
                    val database = databases[spec.stringOf("database")] ?: return null
                    collections[spec.stringOf("id")] = database to spec.stringOf("collectionName")
                }

                else -> {
                    return null
                }
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

        val reasons =
            requirements.map { requirement ->
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
            val collection =
                client
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
            val target =
                entities[operation.stringOf("object")]
                    ?: error("$name: неизвестная сущность ${operation.stringOf("object")}")
            val collection = client.getDatabase(target.first).getCollection(target.second)
            val arguments = operation["arguments"] as? BsonDocument ?: BsonDocument()

            val expectsError = operation["expectError"] != null
            val actual =
                try {
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
    ): BsonValue =
        when (name) {
            "insertOne" -> {
                BsonDocument(
                    "insertedId" to collection.insertOne(arguments.documentOf("document")).insertedId,
                )
            }

            "insertMany" -> {
                val documents = arguments.arrayOf("documents").filterIsInstance<BsonDocument>()
                val result = collection.insertMany(documents, ordered = arguments.flagOf("ordered", default = true))
                // Официальный формат ждёт insertedIds документом «индекс → _id», а не списком.
                BsonDocument(
                    "insertedIds" to
                        BsonDocument(
                            result.insertedIds.mapIndexed { index, id -> index.toString() to id },
                        ),
                )
            }

            "updateOne" -> {
                collection
                    .updateOne(
                        arguments.documentOf("filter"),
                        arguments.documentOf("update"),
                        upsert = arguments.flagOf("upsert", default = false),
                    ).let {
                        BsonDocument(
                            "matchedCount" to BsonInt32(it.matchedCount.toInt()),
                            "modifiedCount" to BsonInt32(it.modifiedCount.toInt()),
                            "upsertedCount" to BsonInt32(if (it.upsertedId == null) 0 else 1),
                        ).let { base ->
                            // upsertedId сценарии ждут только когда апсерт случился.
                            if (it.upsertedId == null) {
                                base
                            } else {
                                BsonDocument(base.entries + ("upsertedId" to it.upsertedId))
                            }
                        }
                    }
            }

            "deleteOne" -> {
                BsonDocument(
                    "deletedCount" to
                        BsonInt32(collection.deleteOne(arguments.documentOf("filter")).deletedCount.toInt()),
                )
            }

            "updateMany" -> {
                collection
                    .updateMany(
                        arguments.documentOf("filter"),
                        arguments.documentOf("update"),
                        upsert = arguments.flagOf("upsert", default = false),
                    ).toResult()
            }

            "replaceOne" -> {
                collection
                    .replaceOne(
                        arguments.documentOf("filter"),
                        arguments.documentOf("replacement"),
                        upsert = arguments.flagOf("upsert", default = false),
                    ).toResult()
            }

            "deleteMany" -> {
                BsonDocument(
                    "deletedCount" to
                        BsonInt32(collection.deleteMany(arguments.documentOf("filter")).deletedCount.toInt()),
                )
            }

            // Сценарии ждут сам документ, а не обёртку; отсутствие совпадения — BSON-null.
            "findOneAndUpdate" -> {
                collection.findOneAndUpdate(
                    arguments.documentOf("filter"),
                    arguments.documentOf("update"),
                    arguments.returnDocument(),
                    upsert = arguments.flagOf("upsert", default = false),
                    sort = arguments["sort"] as? BsonDocument,
                    projection = arguments["projection"] as? BsonDocument,
                ) ?: BsonNull
            }

            "findOneAndReplace" -> {
                collection.findOneAndReplace(
                    arguments.documentOf("filter"),
                    arguments.documentOf("replacement"),
                    arguments.returnDocument(),
                    upsert = arguments.flagOf("upsert", default = false),
                    sort = arguments["sort"] as? BsonDocument,
                    projection = arguments["projection"] as? BsonDocument,
                ) ?: BsonNull
            }

            "findOneAndDelete" -> {
                collection.findOneAndDelete(arguments.documentOf("filter")) ?: BsonNull
            }

            "distinct" -> {
                BsonArray(
                    collection.distinct(
                        (arguments["fieldName"] as? BsonString)?.value ?: error("distinct без fieldName"),
                        arguments.documentOf("filter"),
                    ),
                )
            }

            "estimatedDocumentCount" -> {
                BsonInt64(collection.estimatedDocumentCount())
            }

            "find" -> {
                var query = collection.find(arguments.documentOf("filter"))
                arguments.intOf("skip")?.let { query = query.skip(it) }
                arguments.intOf("limit")?.let { query = query.limit(it) }
                arguments.intOf("batchSize")?.let { query = query.batchSize(it) }
                (arguments["sort"] as? BsonDocument)?.let { query = query.sort(it) }
                BsonArray(query.toList())
            }

            "aggregate" -> {
                var pipeline =
                    collection.aggregate(arguments.arrayOf("pipeline").filterIsInstance<BsonDocument>())
                arguments.intOf("batchSize")?.let { pipeline = pipeline.batchSize(it) }
                (arguments["allowDiskUse"] as? BsonBoolean)?.let { pipeline = pipeline.allowDiskUse(it.value) }
                (arguments["let"] as? BsonDocument)?.let { pipeline = pipeline.let(it) }
                // Любой тип BSON, а не только строка: сценарий с документом-комментарием
                // иначе проходил бы, молча не отправив комментарий вовсе.
                arguments["comment"]?.let { pipeline = pipeline.comment(it) }
                (arguments["hint"] as? BsonDocument)?.let { pipeline = pipeline.hint(it) }
                BsonArray(pipeline.toList())
            }

            "bulkWrite" -> {
                val result =
                    collection.bulkWrite(
                        arguments.arrayOf("requests").filterIsInstance<BsonDocument>().map(::writeModel),
                        ordered = arguments.flagOf("ordered", default = true),
                    )
                BsonDocument(
                    "insertedCount" to BsonInt32(result.insertedCount.toInt()),
                    "matchedCount" to BsonInt32(result.matchedCount.toInt()),
                    "modifiedCount" to BsonInt32(result.modifiedCount.toInt()),
                    "deletedCount" to BsonInt32(result.deletedCount.toInt()),
                    "upsertedCount" to BsonInt32(result.upsertedCount.toInt()),
                    "insertedIds" to BsonDocument(result.insertedIds.map { it.key.toString() to it.value }),
                    "upsertedIds" to BsonDocument(result.upsertedIds.map { it.key.toString() to it.value }),
                )
            }

            "countDocuments" -> {
                BsonInt64(collection.countDocuments(arguments.documentOf("filter")))
            }

            else -> {
                error("операция '$name' не поддержана — должна была отсеяться раньше")
            }
        }

    /**
     * Разбирает одну операцию `bulkWrite` из официального формата.
     *
     * В спецификации операция записана документом с единственным ключом-именем:
     * `{"insertOne": {"document": …}}`. Неизвестное имя — ошибка, а не пропуск: сюда сценарий
     * доходит уже после отсева в [unsupportedOperation], и молчаливый пропуск операции сделал бы
     * тест зелёным, проверив не то.
     */
    private fun writeModel(request: BsonDocument): WriteModel<Document> {
        val kind = request.keys.singleOrNull() ?: error("операция bulkWrite не с одним ключом: ${request.keys}")
        val body = request[kind] as? BsonDocument ?: error("тело операции '$kind' не документ")
        return when (kind) {
            "insertOne" -> {
                InsertOneModel(body.documentOf("document"))
            }

            "deleteOne" -> {
                DeleteOneModel(body.documentOf("filter"))
            }

            "deleteMany" -> {
                DeleteManyModel(body.documentOf("filter"))
            }

            "updateOne" -> {
                UpdateOneModel(
                    body.documentOf("filter"),
                    body.documentOf("update"),
                    upsert = body.flagOf("upsert", default = false),
                )
            }

            "updateMany" -> {
                UpdateManyModel(
                    body.documentOf("filter"),
                    body.documentOf("update"),
                    upsert = body.flagOf("upsert", default = false),
                )
            }

            "replaceOne" -> {
                ReplaceOneModel(
                    body.documentOf("filter"),
                    body.documentOf("replacement"),
                    upsert = body.flagOf("upsert", default = false),
                )
            }

            else -> {
                error("операция bulkWrite '$kind' не поддержана")
            }
        }
    }

    /**
     * Причина пропустить сценарий из-за `expectEvents`, либо `null`.
     *
     * Пропускается всё, чего мы не умеем сверять **точно**: другие типы событий и незнакомые
     * операторы сопоставления. Альтернатива — сравнить как получится — давала бы либо ложные
     * падения, либо, что хуже, ложные успехи.
     */
    private fun unsupportedEvents(case: BsonDocument): String? {
        for (entry in case.arrayOf("expectEvents")) {
            val expectation = entry as? BsonDocument ?: return "expectEvents: элемент не документ"
            for (event in expectation.arrayOf("events")) {
                val document = event as? BsonDocument ?: return "expectEvents: событие не документ"
                val kind = document.keys.singleOrNull() ?: return "expectEvents: событие не с одним ключом"
                if (kind != "commandStartedEvent") return "expectEvents: событие '$kind' не проверяем"
                val body = document[kind] as? BsonDocument ?: return "expectEvents: тело события не документ"
                val unknown = unknownOperators(body)
                if (unknown != null) return "expectEvents: оператор '$unknown' не поддержан"
            }
        }
        return null
    }

    /** Первый встреченный `$$`-оператор, которого нет у [SpecMatcher], либо `null`. */
    private fun unknownOperators(value: BsonValue): String? =
        when (value) {
            is BsonDocument -> {
                value.entries.firstNotNullOfOrNull { (key, nested) ->
                    if (key.startsWith("\$\$") && key !in SUPPORTED_OPERATORS) key else unknownOperators(nested)
                }
            }

            is BsonArray -> {
                value.values.firstNotNullOfOrNull(::unknownOperators)
            }

            else -> {
                null
            }
        }

    /**
     * Сверяет отправленные команды с ожидаемыми.
     *
     * Список сравнивается целиком и по порядку: лишняя или пропущенная команда — расхождение,
     * даже если все ожидаемые нашлись. Сценарии на пакетную выборку только этим и проверяются —
     * что драйвер сходил на сервер столько раз, сколько нужно.
     */
    private fun verifyEvents(
        case: BsonDocument,
        observed: List<CommandStartedEvent>,
        name: String,
    ) {
        for (entry in case.arrayOf("expectEvents")) {
            val expectation = entry as? BsonDocument ?: continue
            val expected = expectation.arrayOf("events").filterIsInstance<BsonDocument>()

            check(expected.size == observed.size) {
                "$name: ожидалось ${expected.size} команд, отправлено ${observed.size} " +
                    "(${observed.map { it.commandName }})"
            }
            expected.forEachIndexed { index, event ->
                val body = event["commandStartedEvent"] as? BsonDocument ?: return@forEachIndexed
                val actual = observed[index]
                (body["commandName"] as? BsonString)?.let {
                    check(it.value == actual.commandName) {
                        "$name: команда #$index — ждали '${it.value}', отправлена '${actual.commandName}'"
                    }
                }
                (body["databaseName"] as? BsonString)?.let {
                    check(it.value == actual.databaseName) {
                        "$name: команда #$index — ждали базу '${it.value}', отправлена '${actual.databaseName}'"
                    }
                }
                (body["command"] as? BsonDocument)?.let { expectedCommand ->
                    check(SpecMatcher.matches(expectedCommand, actual.command, root = true)) {
                        "$name: команда #$index не совпала.\nждали: $expectedCommand\nотправлено: ${actual.command}"
                    }
                }
            }
        }
    }

    private suspend fun verifyOutcome(
        case: BsonDocument,
        name: String,
    ) {
        for (entry in case.arrayOf("outcome")) {
            val expected = entry as? BsonDocument ?: continue
            val collection =
                client
                    .getDatabase(expected.stringOf("databaseName"))
                    .getCollection(expected.stringOf("collectionName"))

            val actual = collection.find().toList().sortedBy { it["_id"]?.toString() }
            val wanted =
                expected
                    .arrayOf("documents")
                    .filterIsInstance<BsonDocument>()
                    .sortedBy { it["_id"]?.toString() }

            check(actual.size == wanted.size) {
                "$name: в коллекции ${expected.stringOf(
                    "collectionName",
                )} ${actual.size} документов, ждали ${wanted.size}"
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

    private fun skipAll(
        file: BsonDocument,
        fileName: String,
        reason: String,
    ) {
        for (test in file.arrayOf("tests")) {
            val case = test as? BsonDocument ?: continue
            report.skipped += "$fileName :: ${case.stringOf("description")}" to reason
        }
    }

    private fun BsonDocument.arrayOf(key: String): List<BsonValue> = (this[key] as? BsonArray)?.values.orEmpty()

    private fun BsonDocument.stringOf(key: String): String =
        (this[key] as? BsonString)?.value ?: error("ожидалась строка в поле \"$key\": $this")

    private fun BsonDocument.returnDocument(): ReturnDocument =
        if ((this["returnDocument"] as? BsonString)?.value == "After") ReturnDocument.AFTER else ReturnDocument.BEFORE

    private fun UpdateResult.toResult(): BsonDocument {
        val base =
            BsonDocument(
                "matchedCount" to BsonInt32(matchedCount.toInt()),
                "modifiedCount" to BsonInt32(modifiedCount.toInt()),
                "upsertedCount" to BsonInt32(if (upsertedId == null) 0 else 1),
            )
        val id = upsertedId ?: return base
        return BsonDocument(base.entries + ("upsertedId" to id))
    }

    private fun BsonDocument.flagOf(
        key: String,
        default: Boolean,
    ): Boolean = (this[key] as? BsonBoolean)?.value ?: default

    private fun BsonDocument.intOf(key: String): Int? =
        when (val value = this[key]) {
            is BsonInt32 -> value.value
            is BsonInt64 -> value.value.toInt()
            else -> null
        }

    private fun BsonDocument.documentOf(key: String): Document = this[key] as? BsonDocument ?: BsonDocument()

    private companion object {
        /** Операторы сопоставления, реализованные в [SpecMatcher]. */
        val SUPPORTED_OPERATORS = setOf("\$\$unsetOrMatches", "\$\$type", "\$\$exists")

        /** Требования, которые раннер умеет вычислять. Остальные — повод пропустить. */
        val KNOWN_REQUIREMENTS = setOf("minServerVersion", "maxServerVersion")

        /** Операция → аргументы, которые mongkn действительно учитывает. */
        val SUPPORTED: Map<String, Set<String>> =
            mapOf(
                "insertOne" to setOf("document"),
                "insertMany" to setOf("documents", "ordered"),
                "updateOne" to setOf("filter", "update", "upsert"),
                "deleteOne" to setOf("filter"),
                "find" to setOf("filter", "limit", "skip", "sort", "batchSize"),
                "countDocuments" to setOf("filter"),
                "updateMany" to setOf("filter", "update", "upsert"),
                "replaceOne" to setOf("filter", "replacement", "upsert"),
                "deleteMany" to setOf("filter"),
                "findOneAndUpdate" to setOf("filter", "update", "returnDocument", "upsert", "sort", "projection"),
                "findOneAndReplace" to setOf("filter", "replacement", "returnDocument", "upsert", "sort", "projection"),
                "findOneAndDelete" to setOf("filter", "sort", "projection"),
                "distinct" to setOf("fieldName", "filter"),
                "estimatedDocumentCount" to emptySet(),
                // Веха M12. `pipeline` обязателен, остальное — опции, которые мы учитываем.
                "aggregate" to setOf("pipeline", "batchSize", "allowDiskUse", "let", "comment", "hint"),
                "bulkWrite" to setOf("requests", "ordered"),
            )
    }
}
