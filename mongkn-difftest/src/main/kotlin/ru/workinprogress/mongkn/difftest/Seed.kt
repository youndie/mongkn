@file:JvmName("Seed")

package ru.workinprogress.mongkn.difftest

import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.BsonDocument
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.io.File

/**
 * Фаза A дифференциального теста: **эталон пишет**.
 *
 * Официальный драйвер кладёт [ReferenceDocument] в `mongkn_diff.reference`, читает его обратно
 * и выгружает в canonical extended JSON. Этот файл — то, с чем нативная сторона будет сверяться.
 *
 * Выгружаем именно **прочитанное с сервера**, а не то, что собрали в памяти: так в эталон
 * попадает всё, что мог сделать по дороге сам сервер.
 *
 * Аргументы: `<connection-string> <путь к файлу фикстуры>`.
 */
public fun main(args: Array<String>): Unit = runBlocking {
    val uri = args.getOrElse(0) { "mongodb://127.0.0.1:27017" }
    val fixture = File(args.getOrElse(1) { "build/diff/reference.json" })

    MongoClient.create(uri).use { client ->
        val database = client.getDatabase(ReferenceDocument.DATABASE)

        // Обе коллекции чистим здесь: фаза A — единственная точка, которая гарантированно
        // выполняется первой, а mongod живёт дольше прогона.
        database.getCollection<BsonDocument>(ReferenceDocument.REFERENCE).drop()
        database.getCollection<BsonDocument>(ReferenceDocument.WRITTEN).drop()

        val reference = database.getCollection<BsonDocument>(ReferenceDocument.REFERENCE)
        reference.insertOne(ReferenceDocument.build())

        val stored = reference.find(BsonDocument()).firstOrNull()
            ?: error("эталонный документ не прочитался обратно")

        fixture.parentFile.mkdirs()
        fixture.writeText(
            stored.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build())
        )
        println("diff: эталон записан, фикстура -> ${fixture.absolutePath}")
    }
}
