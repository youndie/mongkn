---
id: mongkn-difftest
title: mongkn-difftest
type: service
status: active
module: ":mongkn-difftest"
tech_stack: [Kotlin/JVM, mongodb-driver-kotlin-coroutine]
owner: unassigned
depends_on:
  - mongodb-driver-kotlin-coroutine 5.9.1 (эталонная реализация)
  - mongod
publishes: []
---

# mongkn-difftest

## 1. Зона ответственности

Отвечает на вопрос, на который не отвечают ни обычные интеграционные тесты, ни бывший генератор:
**делает ли mongkn то же самое, что делает официальный драйвер.**

Обычные тесты проверяют, что `deletedCount` равен единице, потому что так решил автор. Здесь
эталон — работающая реализация MongoDB.

Чем не занимается: не проверяет соответствие спецификации MongoDB — это отдельная задача M-30
(раннер unified test format). Дифференциальный тест ловит расхождение с конкретной реализацией,
а не с документом.

## 2. Почему три фазы, а не один тест

Официальный драйвер живёт только на JVM, mongkn — только на Native. **В одном процессе их
не свести**, поэтому круг замкнут через общий mongod и файл-фикстуру:

| Фаза | Где | Что делает |
|---|---|---|
| A | `:mongkn-difftest:seedDiffReference` | эталон пишет документ в `mongkn_diff.reference` и выгружает его canonical extended JSON |
| B | `:mongkn-core` → `MongoDifferentialTest` | mongkn читает тот же документ и сверяет с фикстурой (**декодер**), затем пишет свою копию в `mongkn_diff.written` |
| C | `:mongkn-difftest:verifyDiffWritten` | эталон читает написанное и сверяет с собой (**кодировщик**) |

Порядок задан зависимостями задач (`dependsOn` / `finalizedBy` в `:mongkn-core`), а не
соглашением «запускайте по очереди»: дифференциальный тест, молча прошедший на вчерашней
фикстуре, хуже отсутствующего.

Путь к фикстуре нативная сторона получает переменной окружения `MONGKN_DIFF_FIXTURE` —
Gradle-свойства ей недоступны. Если переменной нет, тест падает, а не пропускается.

## 3. Ключевые файлы

| Файл | Что там |
|---|---|
| [ReferenceDocument.kt](../../mongkn-difftest/src/main/kotlin/io/github/mongkn/difftest/ReferenceDocument.kt) | эталонный документ: по полю на каждый поддержанный тип BSON |
| [Seed.kt](../../mongkn-difftest/src/main/kotlin/io/github/mongkn/difftest/Seed.kt) | фаза A |
| [Verify.kt](../../mongkn-difftest/src/main/kotlin/io/github/mongkn/difftest/Verify.kt) | фаза C, расхождения печатаются по полям |
| [MongoDifferentialTest.kt](../../mongkn-core/src/nativeTest/kotlin/io/github/mongkn/MongoDifferentialTest.kt) | фаза B |

## 4. Локальный запуск

Отдельно ничего запускать не нужно: фазы подцеплены к `:mongkn-core:build`. Нужен mongod
на `127.0.0.1:27017` (переопределяется `-Pmongkn.diff.uri=…`).

## 5. Сознательные ограничения / грабли

* **Эталонный документ описан дважды** — в `ReferenceDocument` (JVM) и в `MongoDifferentialTest`
  (Native). Это не дублирование по недосмотру: смысл дифференциального теста в том, что две
  независимые реализации сошлись. Переиспользование данных одной стороной у другой убило бы
  проверку.
* **Фикстура разбирается самой libbson** (`bson_json_reader_new_from_file`), а не Kotlin-парсером:
  парсер тащить не надо, он уже слинкован, и сравнение идёт по значениям, а не по тексту —
  различия в пробелах и экранировании ничего не ломают.
* **Проверено, что тест умеет краснеть.** Подмена `int32` на `int64` в ожидаемом документе роняет
  и фазу B, и фазу C с точным указанием поля. Зелёный дифференциальный тест, который не умеет
  краснеть, бесполезен — при правках стоит повторять эту проверку.
* **Задачи фаз A и C помечены `upToDateWhen { false }`**: состояние mongod они не контролируют,
  и Gradle не должен считать их результат актуальным по наличию выходного файла.
