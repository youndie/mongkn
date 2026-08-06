package ru.workinprogress.mongkn.spec

import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.document

/**
 * Топология развёртывания — для `runOnRequirements` в spec-тестах.
 *
 * Именами из спецификации, а не своими: они попадают в сравнение с полем `topologies` как есть.
 * `load-balanced` не заведён намеренно — это Atlas Serverless, которого у нас нет и проверить
 * который нечем; сценарий, требующий его, обязан пропуститься, а не совпасть с чем-то похожим.
 */
internal enum class Topology(
    val wire: String,
) {
    SINGLE("single"),
    REPLICA_SET("replicaset"),
    SHARDED("sharded"),
}

/**
 * Определяет топологию по ответу `hello`.
 *
 * Признаки — те же, по которым её определяет сам драйвер: mongos представляется `isdbgrid`
 * в поле `msg`, член реплика-сета сообщает `setName`, а одиночный сервер не сообщает ничего
 * из этого. Порядок проверок важен: mongos тоже стоит перед реплика-сетами, но `setName`
 * в его ответе нет, а вот обратное — узел реплика-сета с `msg` — исключено.
 */
internal suspend fun topology(client: MongoClient): Topology {
    val hello = client.getDatabase("admin").runCommand(document { put("hello", 1) })
    return when {
        (hello["msg"] as? BsonString)?.value == "isdbgrid" -> Topology.SHARDED
        hello["setName"] is BsonString -> Topology.REPLICA_SET
        else -> Topology.SINGLE
    }
}
