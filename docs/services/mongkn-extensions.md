---
id: mongkn-extensions
title: mongkn-extensions
type: service
status: active
module: ":mongkn-extensions"
tech_stack: [Kotlin/Native]
owner: unassigned
depends_on:
  - mongkn-core
publishes: []
---

# mongkn-extensions

## 1. Зона ответственности

Инфиксный DSL фильтров и обновлений: `Person::born gt 1900`, `Person::name setTo "Ada"`.

**Отдельный артефакт, а не часть ядра** — решение Р7. Ядро отвечает за форму API, снятую
с официального драйвера; эргономика кладётся сверху и может меняться, не трогая зеркало.
К той же схеме пришёл вендор, вынеся `mongodb-driver-kotlin-extensions` из основного драйвера.

C-кода здесь нет вовсе: модуль работает только с `Document` и `BsonValue`.

## 2. Ключевые файлы

| Файл | Что там |
|---|---|
| [Filters.kt](../../mongkn-extensions/src/nativeMain/kotlin/ru/workinprogress/mongkn/ext/Filters.kt) | `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `within`, `exists`, `and`/`or`/`not` |
| [Updates.kt](../../mongkn-extensions/src/nativeMain/kotlin/ru/workinprogress/mongkn/ext/Updates.kt) | `setTo`, `incBy`, `unset`, `combine` |

## 3. Сознательные ограничения / грабли

* **`@SerialName` не учитывается.** Имя поля берётся из `KProperty1.name`, то есть из имени
  свойства Kotlin. Если класс размечен `@SerialName`, в базе лежит другое имя, и фильтр
  **молча** ничего не найдёт: для MongoDB несуществующее поле — просто «не совпало».
  Связать свойство с элементом дескриптора без рефлексии на Kotlin/Native нельзя. До решения
  (M-36) — либо не используйте `@SerialName` вместе с DSL, либо задавайте поле строкой:
  строковая перегрузка есть у каждой операции.
* **`and` не сливает условия в один документ.** Два условия на одно поле имеют одинаковый ключ
  и затёрли бы друг друга, поэтому `$and` явный.
* **`combine` сливает одноимённые операторы.** Документ с двумя ключами `$set` MongoDB
  не принимает, а наш `BsonDocument` их допускает — без слияния ошибка вылезла бы только
  на сервере.
* **`Any?` в значениях фильтра допустим**, в отличие от документов (решение Р4): фильтр живёт
  один запрос и обратно не читается, потерям типа взяться неоткуда. Незнакомый тип роняет
  вызов сразу.
