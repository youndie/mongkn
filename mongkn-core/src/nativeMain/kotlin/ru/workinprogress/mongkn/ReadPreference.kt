package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document

/** Куда направлять чтение — `mongoc_read_mode_t`. */
public enum class ReadPreferenceMode(
    internal val wire: String,
) {
    /** Только на первичный узел. Умолчание. */
    PRIMARY("primary"),

    /** На первичный, а если недоступен — на вторичный. */
    PRIMARY_PREFERRED("primaryPreferred"),

    /** Только на вторичный. На одноузловом развёртывании выбор сервера не удастся. */
    SECONDARY("secondary"),

    /** На вторичный, а если их нет — на первичный. */
    SECONDARY_PREFERRED("secondaryPreferred"),

    /** На ближайший по задержке, независимо от роли. */
    NEAREST("nearest"),
}

/**
 * Предпочтение чтения — куда направлять запрос в развёртывании из нескольких узлов.
 *
 * Задаётся через [MongoCollection.withReadPreference]. В отличие от `readConcern` и `writeConcern`
 * **не является ключом документа опций**: libmongoc принимает его отдельным параметром, а
 * положенный в опции ключ `readPreference` уезжает на сервер дословно, и тот отвечает
 * `BSON field 'FindCommandRequest.readPreference' is an unknown field`. Проверено пробой —
 * отсюда и отдельный тип вместо строчки в [Concerns].
 *
 * Влияет только на **чтение**: `find`, `aggregate`, `countDocuments`, `distinct`,
 * `estimatedDocumentCount`. Запись всегда идёт на первичный узел, это правило сервера.
 *
 * @param tags отбор узлов по меткам в конфигурации реплика-сета; пустой список — без отбора.
 * @param maxStalenessSeconds насколько узел вправе отставать от первичного. `null` — без предела;
 *   сервер требует значения не меньше 90 секунд.
 */
public class ReadPreference(
    public val mode: ReadPreferenceMode,
    public val tags: List<Document> = emptyList(),
    public val maxStalenessSeconds: Long? = null,
) {
    /**
     * Описание для внутреннего употребления.
     *
     * Документ, а не набор полей, чтобы предпочтение проходило по тем же путям, что и остальные
     * настройки коллекции, и не размножало сигнатуры операций.
     */
    internal fun describe(): Document =
        BsonDocument(
            listOfNotNull(
                "mode" to BsonString(mode.wire),
                if (tags.isEmpty()) null else "tags" to BsonArray(tags),
                maxStalenessSeconds?.let { "maxStalenessSeconds" to BsonInt64(it) },
            ),
        )
}
