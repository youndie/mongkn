package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document

/*
 * Гарантии записи и чтения.
 *
 * Собственных типов под них нет намеренно: на проводе это обычные документы строго той формы,
 * которую описывает сервер, и заводить параллельную систему типов значило бы переводить
 * документацию MongoDB на свой язык, а потом расходиться с ней при каждом обновлении.
 * Здесь только фабрики, чтобы не собирать документ вручную.
 */

/**
 * `writeConcern` — сколько узлов должны подтвердить запись.
 *
 * @param w число узлов или строка вроде `"majority"`.
 * @param journal ждать ли записи в журнал.
 * @param timeoutMillis сколько ждать подтверждения. **Не** отменяет саму запись по истечении:
 *   сервер вернёт ошибку, но операция может примениться — так устроен сам `wtimeout`.
 */
public fun writeConcern(
    w: BsonValue,
    journal: Boolean? = null,
    timeoutMillis: Int? = null,
): Document =
    Document(
        listOfNotNull(
            "w" to w,
            journal?.let { "j" to BsonBoolean(it) },
            timeoutMillis?.let { "wtimeout" to BsonInt32(it) },
        ),
    )

/** Ждать подтверждения от большинства узлов. */
public fun majorityWriteConcern(timeoutMillis: Int? = null): Document =
    writeConcern(BsonString("majority"), timeoutMillis = timeoutMillis)

/**
 * `readConcern` — насколько «устоявшиеся» данные читать.
 *
 * @param level `"local"`, `"majority"`, `"linearizable"`, `"available"`, `"snapshot"`.
 */
public fun readConcern(level: String): Document = Document(listOf("level" to BsonString(level)))
