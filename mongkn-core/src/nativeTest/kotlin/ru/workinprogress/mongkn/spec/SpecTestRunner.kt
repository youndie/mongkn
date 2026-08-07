package ru.workinprogress.mongkn.spec

import kotlinx.coroutines.flow.toList
import ru.workinprogress.mongkn.BulkWriteResult
import ru.workinprogress.mongkn.CommandStartedEvent
import ru.workinprogress.mongkn.DeleteManyModel
import ru.workinprogress.mongkn.DeleteOneModel
import ru.workinprogress.mongkn.InsertOneModel
import ru.workinprogress.mongkn.MongoBulkWriteException
import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.MongoCollection
import ru.workinprogress.mongkn.MongoErrorDomain
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
import ru.workinprogress.mongkn.support.AppNames
import ru.workinprogress.mongkn.support.boundTo

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
 * * `runOnRequirements` умеет версию сервера и топологию; всё прочее (тип аутентификации,
 *   serverless) по-прежнему повод честно пропустить сценарий;
 * * `expectError` сверяется по `errorCode`, `isClientError` и частичному результату пакетной
 *   записи; ожидание с ключом, которого мы не разбираем, — повод пропустить сценарий (M-80).
 *   До M-80 проверялся лишь сам факт отказа, то есть сценарий про код ошибки был зелёным
 *   при **любой** ошибке;
 * * из операций уровня раннера реализована одна — `failPoint`; сущностей поддержаны коллекция,
 *   база и `testRunner`, но не курсоры и не сессии.
 */
internal class SpecTestRunner(
    /** Строка подключения без параметров сценария — основа для клиента файла. */
    private val uri: String,
    private val shared: MongoClient,
    /** Записывает команды клиента — источник правды для `expectEvents`. */
    private val recorder: SpecEventRecorder,
    /** Версия сервера покомпонентно — для `runOnRequirements`. */
    private val version: List<Int>,
    /** Топология развёртывания — вторая половина `runOnRequirements`. */
    private val topology: Topology,
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

    /** Имена failpoint'ов, поставленных сценарием, — чтобы снять их, чем бы он ни кончился. */
    private val configuredFailPoints = mutableSetOf<String>()

    /**
     * Клиент текущего файла.
     *
     * Обычно это [shared] — один клиент на весь прогон. Файл, объявивший `uriOptions`, получает
     * свой: настройки подключения там не украшение. `retryReads: false` в
     * `estimatedDocumentCount.json` — ровно то, без чего сценарий с обрывом соединения проверяет
     * не то, что написано: драйвер молча повторяет чтение на новом соединении, операция проходит,
     * и «ожидалась ошибка» превращается в зелёное «ошибки не было». Найдено прогоном (M-80).
     */
    private var client: MongoClient = shared

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

        // Свой клиент — только если файл просил особых настроек подключения. Наблюдатель тот же:
        // `expectEvents` сверяется по командам того клиента, которым сценарий и выполнялся.
        val own =
            entities.uriOptions
                .takeIf { it.isNotEmpty() }
                ?.let { options ->
                    // Основа приходит уже с параметрами, но полагаться на это незачем: строка
                    // без `?` дала бы `mongodb://host&retryReads=false`, то есть хост с амперсандом.
                    val separator = if ('?' in uri) "&" else "/?"
                    MongoClient(uri + separator + options.joinToString("&"), commandListener = recorder)
                }
        client = own ?: shared
        try {
            runTests(file, fileName, entities)
        } finally {
            client = shared
            own?.close()
        }
    }

    private suspend fun runTests(
        file: BsonDocument,
        fileName: String,
        entities: Entities,
    ) {
        for (test in file.arrayOf("tests")) {
            val case = test as? BsonDocument ?: continue
            val name = "$fileName :: ${case.stringOf("description")}"

            val skipReason =
                unmetRequirements(file)
                    ?: unmetRequirements(case)
                    ?: unsupportedOperation(case, entities)
                    ?: unsupportedEvents(case)
            if (skipReason != null) {
                report.skipped += name to skipReason
                continue
            }

            seedInitialData(file)
            // Чистится после засева: события подготовки к сценарию не относятся.
            recorder.clear()
            // Failpoint, оставшийся включённым, сломал бы следующие сценарии, а искать причину
            // пришлось бы в них — поэтому снимается и после падения тоже.
            try {
                runOperations(case, entities, name)
            } finally {
                disableFailPoints()
            }
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

    /**
     * Сущности сценария: id → адрес.
     *
     * Базы держатся наравне с коллекциями, а не только как ступень к ним: операции `dropCollection`
     * и `createCollection` объявлены в формате **на базе**, и без этой половины они выглядели бы
     * как «объект не поддержан» (так и было до M-80).
     */
    private class Entities(
        /** id → (база, коллекция). */
        val collections: Map<String, Pair<String, String>>,
        /** id → имя базы. */
        val databases: Map<String, String>,
        /** Параметры строки подключения, затребованные файлом; пусто — годится общий клиент. */
        val uriOptions: List<String>,
    )

    /** `null`, если файл использует сущность, которой у нас нет. */
    private fun collectEntities(file: BsonDocument): Entities? {
        val databases = mutableMapOf<String, String>()
        val collections = mutableMapOf<String, Pair<String, String>>()
        val uriOptions = mutableListOf<String>()

        for (entry in file.arrayOf("createEntities")) {
            val entity = entry as? BsonDocument ?: return null
            val (kind, body) = entity.entries.firstOrNull() ?: return null
            val spec = body as? BsonDocument ?: return null
            when (kind) {
                // Клиент один на файл; из его настроек умеем то, что переводится в строку
                // подключения. Настройку, которой не умеем, пропускаем **файлом**: молча
                // подключиться иначе, чем просит сценарий, — это проверять не тот драйвер.
                "client" -> {
                    val requested = spec["uriOptions"] as? BsonDocument ?: BsonDocument()
                    for ((option, value) in requested.entries) {
                        when {
                            option == "retryReads" && value is BsonBoolean -> {
                                uriOptions += "retryReads=${value.value}"
                            }

                            else -> {
                                return null
                            }
                        }
                    }
                }

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
        return Entities(collections, databases, uriOptions)
    }

    /**
     * Проверяет `runOnRequirements`: возвращает причину пропуска или `null`, если требования сняты.
     *
     * Умеет версию сервера и топологию. Всё остальное (тип аутентификации, serverless) честно
     * отказывается выполнять сценарий, а не делает вид, что требование выполнено: молчаливое
     * «подходит» превратило бы непроверенное в зелёное.
     */
    private fun unmetRequirements(node: BsonDocument): String? {
        val requirements = node.arrayOf("runOnRequirements").filterIsInstance<BsonDocument>()
        if (requirements.isEmpty()) return null

        val reasons =
            requirements.map { requirement ->
                val unknown = requirement.keys - KNOWN_REQUIREMENTS
                when {
                    unknown.isNotEmpty() -> "runOnRequirements: не умеем проверять ${unknown.sorted()}"
                    !satisfiesVersion(requirement) -> "runOnRequirements: версия сервера не подходит"
                    !satisfiesTopology(requirement) -> "runOnRequirements: топология ${topology.wire} не подходит"
                    else -> null
                }
            }
        // Требования — список альтернатив: достаточно, чтобы подошла одна.
        return if (reasons.any { it == null }) null else reasons.filterNotNull().first()
    }

    private fun satisfiesVersion(requirement: BsonDocument): Boolean {
        (requirement["minServerVersion"] as? BsonString)?.let {
            if (compareVersions(version, parseVersion(it.value)) < 0) return false
        }
        (requirement["maxServerVersion"] as? BsonString)?.let {
            if (compareVersions(version, parseVersion(it.value)) > 0) return false
        }
        return true
    }

    /**
     * `topologies` — список допустимых топологий; отсутствие ключа означает «любая».
     *
     * Незнакомое имя топологии (`load-balanced`) требование **не** удовлетворяет: мы такого
     * развёртывания не поднимаем, и совпасть с ним нечему.
     */
    private fun satisfiesTopology(requirement: BsonDocument): Boolean {
        val allowed = (requirement["topologies"] as? BsonArray)?.values ?: return true
        return allowed.filterIsInstance<BsonString>().any { it.value == topology.wire }
    }

    private fun unsupportedOperation(
        case: BsonDocument,
        entities: Entities,
    ): String? {
        for (entry in case.arrayOf("operations")) {
            val operation = entry as? BsonDocument ?: return "операция не документ"
            val name = operation.stringOf("name")
            val target = operation.stringOf("object")
            val allowed = SUPPORTED[name] ?: return "операция '$name' не реализована"

            // Сущность ищется в разобранном `createEntities`, а не угадывается по имени: `collection0`
            // и `database0` — соглашение сценариев, а не правило формата, и опираться на него значило
            // бы выполнить операцию над не тем объектом при первом же файле, где принято иначе.
            val kind = OBJECT_KIND[name] ?: return "неизвестен вид объекта для '$name'"
            val known =
                when (kind) {
                    ObjectKind.COLLECTION -> target in entities.collections
                    ObjectKind.DATABASE -> target in entities.databases
                    ObjectKind.TEST_RUNNER -> target == "testRunner"
                }
            if (!known) return "объект '$target' не подходит операции '$name'"

            // Молча проигнорировать незнакомый аргумент опаснее, чем пропустить тест: сценарий
            // с `ordered: false` прошёл бы «успешно», проверив совсем не то, что задумано.
            val arguments = (operation["arguments"] as? BsonDocument)?.keys.orEmpty()
            val extra = arguments - allowed
            if (extra.isNotEmpty()) return "аргументы не поддержаны: ${extra.sorted()}"

            (operation["expectError"] as? BsonDocument)?.let { expected ->
                val unknown = expected.keys - KNOWN_ERROR_EXPECTATIONS
                if (unknown.isNotEmpty()) return "ожидание ошибки не разобрано: ${unknown.sorted()}"
            }
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
        entities: Entities,
        name: String,
    ) {
        for (entry in case.arrayOf("operations")) {
            val operation = entry as? BsonDocument ?: continue
            val operationName = operation.stringOf("name")
            val arguments = operation["arguments"] as? BsonDocument ?: BsonDocument()

            val expectedError = operation["expectError"] as? BsonDocument
            val expectsError = operation["expectError"] != null
            val actual =
                try {
                    perform(operation, operationName, arguments, entities, name)
                } catch (e: MongoException) {
                    check(expectsError) { "$name: операция упала неожиданно: ${e.message}" }
                    expectedError?.let { verifyError(it, e, name) }
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

    /** Выполняет операцию над той сущностью, к которой она относится по формату. */
    private suspend fun perform(
        operation: BsonDocument,
        operationName: String,
        arguments: BsonDocument,
        entities: Entities,
        name: String,
    ): BsonValue {
        val target = operation.stringOf("object")
        return when (OBJECT_KIND[operationName]) {
            ObjectKind.TEST_RUNNER -> {
                configureFailPoint(arguments.documentOf("failPoint"))
                BsonNull
            }

            ObjectKind.DATABASE -> {
                val databaseName = entities.databases[target] ?: error("$name: неизвестная база $target")
                onDatabase(databaseName, operationName, arguments)
            }

            else -> {
                val address = entities.collections[target] ?: error("$name: неизвестная сущность $target")
                invoke(client.getDatabase(address.first).getCollection(address.second), operationName, arguments)
            }
        }
    }

    /**
     * Инсценирует сбой через `configureFailPoint`.
     *
     * Ставится на `admin` того же клиента, которым идёт сценарий, — другого у нас и нет: раннер
     * работает одним клиентом, и аргумент `client` сценария сверять не с чем. Имя failpoint'а
     * запоминается, чтобы снять его после сценария: `mode: {times: 1}` расходуется первой же
     * командой, но полагаться на это нельзя — сценарий вправе не дойти до операции.
     */
    private suspend fun configureFailPoint(failPoint: BsonDocument) {
        // Сбой сужается до клиента раннера: иначе `mode: {times: 1}` вправе съесть соседний тест,
        // а отменённый вызов из другого класса — прийти сюда с опозданием (M-82).
        client.getDatabase("admin").runCommand(failPoint.boundTo(AppNames.SPEC))
        configuredFailPoints += (failPoint["configureFailPoint"] as? BsonString)?.value ?: return
    }

    private suspend fun disableFailPoints() {
        for (failPoint in configuredFailPoints) {
            client.getDatabase("admin").runCommand(
                BsonDocument(
                    "configureFailPoint" to BsonString(failPoint),
                    "mode" to BsonString("off"),
                ),
            )
        }
        configuredFailPoints.clear()
    }

    private suspend fun onDatabase(
        databaseName: String,
        operationName: String,
        arguments: BsonDocument,
    ): BsonValue {
        val database = client.getDatabase(databaseName)
        when (operationName) {
            "dropCollection" -> {
                // Своего `dropCollection` у базы нет — он есть у коллекции, и это та же команда.
                // Отсутствующая коллекция удалению не мешает: сервер отвечает `NamespaceNotFound`,
                // а сценарий пользуется этой операцией именно как уборкой перед созданием.
                try {
                    database.getCollection(arguments.stringOf("collection")).drop()
                } catch (e: MongoException) {
                    if (e.code != NAMESPACE_NOT_FOUND) throw e
                }
            }

            "createCollection" -> {
                // Всё, кроме имени, уходит документом опций: так создаётся и представление
                // (`viewOn` плюс `pipeline`), которое сценарию и нужно.
                val options = BsonDocument(arguments.entries.filterNot { it.first == "collection" })
                database.createCollection(arguments.stringOf("collection"), options)
            }

            else -> {
                error("операция базы '$operationName' не реализована")
            }
        }
        return BsonNull
    }

    /**
     * Сверяет пойманное исключение с `expectError`.
     *
     * До M-80 проверялся только сам факт отказа: сценарий, ждущий кода 8, был зелёным при любой
     * ошибке — в том числе при упавшем соединении вместо ответа сервера. Ключи, которых мы
     * разбирать не умеем, приводят к пропуску сценария (см. [KNOWN_ERROR_EXPECTATIONS]),
     * а не к молчаливому «сошлось».
     */
    private fun verifyError(
        expected: BsonDocument,
        actual: MongoException,
        name: String,
    ) {
        (expected["errorCode"] as? BsonInt32)?.let {
            check(actual.code == it.value.toUInt()) {
                "$name: ожидался код ${it.value}, получен ${actual.code} (${actual.message})"
            }
        }
        (expected["isClientError"] as? BsonBoolean)?.let {
            // Клиентская ошибка — та, которую сервер не присылал: обрыв соединения, отказ выбора
            // сервера, разбор ответа. Домен `SERVER` означает ровно обратное — ответ сервера
            // с кодом MongoDB (M-63), поэтому сравнение доменом точнее любого разбора текста.
            val clientSide = actual.errorDomain != MongoErrorDomain.SERVER
            check(clientSide == it.value) {
                "$name: ожидалась ${if (it.value) "клиентская" else "серверная"} ошибка, " +
                    "получен домен ${actual.errorDomain} (${actual.message})"
            }
        }
        (expected["expectResult"] as? BsonDocument)?.let { wanted ->
            // Частичный результат упавшей пакетной записи. Есть только у [MongoBulkWriteException];
            // обычное исключение его не несёт, и сценарий с таким ожиданием обязан пропуститься,
            // а не сойтись «по умолчанию».
            val partial =
                (actual as? MongoBulkWriteException)?.result
                    ?: error("$name: ожидался частичный результат, а исключение его не несёт")
            check(SpecMatcher.matches(wanted, partial.describe(), root = true)) {
                "$name: частичный результат не совпал\n  ожидалось: $wanted\n  получено:  ${partial.describe()}"
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
                // Опции уходят документом — тем самым, который операция научилась принимать
                // в M-80. Сценарии сверяют не только результат, но и отправленную команду:
                // `maxTimeMS` и `comment` обязаны оказаться в `count`.
                BsonInt64(collection.estimatedDocumentCount(arguments.options()))
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
                result.describe()
            }

            "countDocuments" -> {
                BsonInt64(collection.countDocuments(arguments.documentOf("filter"), arguments.options()))
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

    /**
     * Аргументы сценария, которые в mongkn уходят **документом опций**, а не параметрами.
     *
     * `comment` берётся любого типа BSON, а не только строкой: сервер с 4.4 принимает здесь
     * документ, и сценарий с документом-комментарием, приведённым к строке, проверял бы не то,
     * что написано.
     */
    private fun BsonDocument.options(): Document =
        BsonDocument(
            listOfNotNull(
                this["comment"]?.let { "comment" to it },
                intOf("maxTimeMS")?.let { "maxTimeMS" to BsonInt64(it.toLong()) },
            ),
        )

    /**
     * Результат пакетной записи в том виде, в каком его ждёт официальный формат.
     *
     * Одна функция на два случая: успех сверяется по `expectResult` операции, частичный результат
     * упавшей записи — по `expectResult` **внутри** `expectError`. Формы там одинаковые, и держать
     * их разными реализациями значило бы однажды разойтись.
     */
    private fun BulkWriteResult.describe(): BsonDocument =
        BsonDocument(
            "insertedCount" to BsonInt32(insertedCount.toInt()),
            "matchedCount" to BsonInt32(matchedCount.toInt()),
            "modifiedCount" to BsonInt32(modifiedCount.toInt()),
            "deletedCount" to BsonInt32(deletedCount.toInt()),
            "upsertedCount" to BsonInt32(upsertedCount.toInt()),
            "insertedIds" to BsonDocument(insertedIds.map { it.key.toString() to it.value }),
            "upsertedIds" to BsonDocument(upsertedIds.map { it.key.toString() to it.value }),
        )

    private companion object {
        /** Операторы сопоставления, реализованные в [SpecMatcher]. */
        val SUPPORTED_OPERATORS = setOf("\$\$unsetOrMatches", "\$\$type", "\$\$exists")

        /** Требования, которые раннер умеет вычислять. Остальные — повод пропустить. */
        val KNOWN_REQUIREMENTS = setOf("minServerVersion", "maxServerVersion", "topologies")

        /** Операция → аргументы, которые mongkn действительно учитывает. */
        val SUPPORTED: Map<String, Set<String>> =
            mapOf(
                "insertOne" to setOf("document"),
                "insertMany" to setOf("documents", "ordered"),
                "updateOne" to setOf("filter", "update", "upsert"),
                "deleteOne" to setOf("filter"),
                "find" to setOf("filter", "limit", "skip", "sort", "batchSize"),
                "countDocuments" to setOf("filter", "comment"),
                "updateMany" to setOf("filter", "update", "upsert"),
                "replaceOne" to setOf("filter", "replacement", "upsert"),
                "deleteMany" to setOf("filter"),
                "findOneAndUpdate" to setOf("filter", "update", "returnDocument", "upsert", "sort", "projection"),
                "findOneAndReplace" to setOf("filter", "replacement", "returnDocument", "upsert", "sort", "projection"),
                "findOneAndDelete" to setOf("filter", "sort", "projection"),
                "distinct" to setOf("fieldName", "filter"),
                "estimatedDocumentCount" to setOf("maxTimeMS", "comment"),
                // Веха M12. `pipeline` обязателен, остальное — опции, которые мы учитываем.
                "aggregate" to setOf("pipeline", "batchSize", "allowDiskUse", "let", "comment", "hint"),
                "bulkWrite" to setOf("requests", "ordered"),
                // M-80. Операции не над коллекцией: две над базой и одна над самим раннером.
                "dropCollection" to setOf("collection"),
                "createCollection" to setOf("collection", "viewOn", "pipeline"),
                "failPoint" to setOf("client", "failPoint"),
            )

        /**
         * Операция → сущность, к которой она относится по формату.
         *
         * Таблицей, а не догадкой по имени сущности: `dropCollection` объявлена **на базе**,
         * хотя по названию похожа на операцию коллекции, и наоборот — `drop` бывает у обеих.
         */
        val OBJECT_KIND: Map<String, ObjectKind> =
            SUPPORTED.keys.associateWith { operation ->
                when (operation) {
                    "dropCollection", "createCollection" -> ObjectKind.DATABASE
                    "failPoint" -> ObjectKind.TEST_RUNNER
                    else -> ObjectKind.COLLECTION
                }
            }

        /**
         * Ключи `expectError`, которые раннер действительно проверяет.
         *
         * Всё прочее (`errorLabelsContain`, `errorContains`, `errorResponse`) — повод пропустить
         * сценарий: ожидание, которое мы не сверяем, делает красный тест зелёным.
         */
        val KNOWN_ERROR_EXPECTATIONS = setOf("isError", "isClientError", "errorCode", "expectResult")

        /** `NamespaceNotFound` — удалять нечего; для уборки перед созданием это не ошибка. */
        const val NAMESPACE_NOT_FOUND: UInt = 26u
    }

    /** Вид сущности, над которой выполняется операция. */
    private enum class ObjectKind {
        COLLECTION,
        DATABASE,
        TEST_RUNNER,
    }
}
