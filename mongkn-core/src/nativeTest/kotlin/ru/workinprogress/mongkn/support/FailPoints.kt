package ru.workinprogress.mongkn.support

import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document

/**
 * Адресация инсценированных сбоев конкретному клиенту (M-82).
 *
 * `failCommand` включается **на сервере целиком**, а `mode: {times: 1}` расходуется первой
 * подошедшей командой — от кого угодно. Пока сбой не привязан к отправителю, соседний тест вправе
 * съесть чужое срабатывание, и тогда один тест падает без причины, а другой проходит зря.
 *
 * Опасность не умозрительная. `watch` — это команда `aggregate`, `countDocuments` — тоже, так что
 * совпадают не только одноимённые операции. Хуже: по находке M-68 отменённый вызов продолжает
 * жить своей жизнью и приходит на сервер **позже**, чем закончился породивший его тест. То есть
 * пересечься могут тесты, которые по коду не пересекаются никак, — и воспроизводится такое
 * раз в сотню прогонов.
 *
 * Лечится штатным средством самого сервера: `data.appName` в `failCommand` сужает сбой до
 * клиента, представившегося этим именем (MongoDB 4.4+). Имя должно попасть в **оба** места —
 * в строку подключения и в описание сбоя, — поэтому оно выдаётся здесь, а не пишется руками.
 */
class AppNames(
    private val prefix: String,
) {
    companion object {
        /**
         * Имя для spec-раннера — одно на весь прогон сценариев.
         *
         * Раздавать имена по файлам незачем: сценарии идут последовательно одним клиентом,
         * и защищаться нужно от **соседних тестовых классов**, а не от самих себя.
         */
        const val SPEC: String = "mongkn-spec"
    }

    private var next = 0
    private val byClient = mutableMapOf<MongoClient, String>()

    /**
     * Выдаёт следующее имя. Его нужно положить в строку подключения (`appName=…`), а клиента,
     * который на ней поднялся, потом отдать в [remember].
     */
    fun assign(): String = "mongkn-$prefix-${next++}"

    fun remember(
        client: MongoClient,
        appName: String,
    ): MongoClient = client.also { byClient[it] = appName }

    fun of(client: MongoClient): String? = byClient[client]

    fun forget(client: MongoClient) {
        byClient.remove(client)
    }
}

/**
 * Дописывает `appName` в строку подключения.
 *
 * Разделитель зависит от того, есть ли в строке параметры: без них нужен `/?`, иначе получится
 * `mongodb://host&appName=…`, то есть хост с амперсандом внутри.
 */
fun bindableUri(
    uri: String,
    appName: String,
): String = uri + (if ('?' in uri) "&" else "/?") + "appName=$appName"

/**
 * Сужает описание сбоя до клиента с именем [appName].
 *
 * Возвращает документ как есть, если имени нет, сбой выключается (`mode: "off"` — там `data`
 * не при чём) или привязка уже задана самим тестом.
 */
fun Document.boundTo(appName: String?): Document {
    if (appName == null) return this
    if ((this["mode"] as? BsonString)?.value == "off") return this

    val data = (this["data"] as? BsonDocument)?.entries.orEmpty()
    if (data.any { it.first == "appName" }) return this

    // `configureFailPoint` обязан остаться первым ключом — это имя команды.
    return BsonDocument(
        entries.filterNot { it.first == "data" } + ("data" to BsonDocument(data + ("appName" to BsonString(appName)))),
    )
}
