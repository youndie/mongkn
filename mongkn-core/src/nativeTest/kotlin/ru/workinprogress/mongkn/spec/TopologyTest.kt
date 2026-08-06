package ru.workinprogress.mongkn.spec

import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Определение топологии — то, на чём держится `runOnRequirements: topologies` (M-66).
 *
 * Проверяется на **двух** контурах сразу, и это единственный способ проверить его вообще:
 * функция, всегда возвращающая одно значение, на одном развёртывании выглядит безупречно.
 * Ошибись она здесь — spec-раннер молча выполнял бы сценарии, предназначенные другой топологии,
 * или так же молча пропускал подходящие.
 */
class TopologyTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(uri: String): MongoClient = MongoClient(uri).also { clients += it }

    @Test
    fun `the main contour is seen as a replica set`() =
        runTest {
            // Без `replicaSet` в строке подключения драйвер считал бы топологию одиночной, но
            // `hello` от этого не меняется: `setName` сервер сообщает в любом случае. Проверяем
            // именно ответ сервера — spec-требование задано про развёртывание, а не про то,
            // как к нему подключились.
            assertEquals(Topology.REPLICA_SET, topology(connect(TestServer.uri())))
        }

    @Test
    fun `the sharded contour is seen as sharded`() =
        runTest {
            assertEquals(Topology.SHARDED, topology(connect(TestServer.shardUri())))
        }
}
