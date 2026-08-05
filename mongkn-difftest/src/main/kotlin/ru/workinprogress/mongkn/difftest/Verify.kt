@file:JvmName("Verify")

package ru.workinprogress.mongkn.difftest

import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.BsonDocument

/**
 * Фаза C дифференциального теста: **эталон читает то, что написал mongkn**.
 *
 * Сравнивается документ из `mongkn_diff.written` (его положила туда нативная сторона) с тем,
 * что официальный драйвер считает эталоном. Совпадение означает, что наш **кодировщик** BSON
 * неотличим от эталонного — включая различие int32/int64 и порядок ключей.
 *
 * Расхождение печатается по полям: «documents differ» без подробностей бесполезно, когда полей
 * шестнадцать.
 */
public fun main(args: Array<String>): Unit =
    runBlocking {
        val uri = args.getOrElse(0) { "mongodb://127.0.0.1:27017" }

        MongoClient.create(uri).use { client ->
            val written =
                client
                    .getDatabase(ReferenceDocument.DATABASE)
                    .getCollection<BsonDocument>(ReferenceDocument.WRITTEN)
                    .find(BsonDocument())
                    .firstOrNull()
                    ?: error(
                        "в ${ReferenceDocument.DATABASE}.${ReferenceDocument.WRITTEN} пусто: " +
                            "нативная сторона не отработала или упала до записи",
                    )

            val expected = ReferenceDocument.build()
            val mismatches =
                buildList {
                    for (key in expected.keys + written.keys) {
                        val left = expected[key]
                        val right = written[key]
                        if (left != right) {
                            add("  $key: эталон=$left (${left?.bsonType}), mongkn=$right (${right?.bsonType})")
                        }
                    }
                    if (expected.keys.toList() != written.keys.toList()) {
                        add("  порядок ключей: эталон=${expected.keys.toList()}, mongkn=${written.keys.toList()}")
                    }
                }

            check(mismatches.isEmpty()) {
                "документ, записанный mongkn, расходится с эталоном:\n" + mismatches.joinToString("\n")
            }
            println("diff: mongkn записал документ, неотличимый от эталонного (${expected.size} полей)")
        }
    }
