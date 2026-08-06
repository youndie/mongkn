# Сериализация: свои типы, пустые поля, фильтры

Что нужно знать, чтобы документ лёг в базу в том виде, в каком вы его задумали, — и чтобы запрос
по нему потом что-то нашёл.

Все находки ниже родом из первой настоящей миграции на mongkn (внутренний сервис). Общее у них одно:
**MongoDB не считает ошибкой ни один из этих случаев.** Документ записывается, запрос
выполняется, ответ приходит пустым.

## Точка расширения: BsonEncoder и BsonDecoder

Тип, у которого в BSON есть точное представление, должен доезжать до документа этим
представлением, а не пересказом. Устроено как `JsonEncoder.encodeJsonElement`
в `kotlinx-serialization-json`: сериализатор узнаёт свой кодировщик и отдаёт готовое значение.

```kotlin
object MoneySerializer : KSerializer<Money> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) {
        val bson = encoder as? BsonEncoder
            ?: throw SerializationException("Money сериализуется только в BSON")
        bson.encodeBsonValue(BsonDecimal128(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): Money {
        val bson = decoder as? BsonDecoder
            ?: throw SerializationException("Money читается только из BSON")
        return Money((bson.decodeBsonValue() as BsonDecimal128).value)
    }
}
```

Проверять тип кодировщика и падать внятно — часть договора, а не перестраховка: тот же
сериализатор могут применить к JSON, и молчаливое приведение дало бы данные, которые не читаются
обратно.

Готовые сериализаторы для самых частых случаев лежат в `mongkn-extensions`:
[`StringAsBsonObjectId`](../../mongkn-extensions/src/nativeMain/kotlin/ru/workinprogress/mongkn/ext/Serializers.kt)
и `InstantAsBsonDateTime`.

## `_id`: нужны обе половины

Сериализатор задаёт **тип** значения, но не **имя** поля. Имя mongkn берёт из
`descriptor.getElementName`, то есть из имени свойства Kotlin, и `id` в `_id` не превращается сам.

```kotlin
@Serializable
data class Doc(
    @SerialName("_id")
    @Serializable(with = StringAsBsonObjectId::class)
    val id: String,
)
```

Без `@SerialName("_id")` документ уходит с обычным полем `id`, а `_id` генерирует сама MongoDB:
тихо, без ошибки и с посторонним значением. Найти такой документ по своему `id` потом нельзя.

## Дата обязана быть датой

TTL-индекс работает только по полю с BSON-датой. Запишите туда строку — индекс не сломается
с ошибкой, он просто **перестанет удалять документы**, и заметить это можно только по растущей
коллекции. Для дат используйте `InstantAsBsonDateTime`, а не `Instant.toString()`.

## Пустые поля и разреженные индексы

Кодировщик пишет пустое поле **явным `null`**, а разреженный (`sparse`) индекс считает
отсутствующим только реально отсутствующий ключ. Отсюда неожиданный результат: уникальный
разреженный индекс по необязательному полю **ловит конфликт на втором документе с `null`** —
для индекса это два одинаковых значения, а не два пропуска.

Рычаг здесь один, и он на стороне модели, а не кодировщика:

```kotlin
@Serializable
data class User(
    val login: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val email: String? = null,
)
```

`@EncodeDefault(NEVER)` убирает поле из документа целиком, когда значение равно умолчанию, —
и тогда `sparse` работает как задумано.

Общего переключателя вроде `explicitNulls` у кодировщика пока нет (M-88): пропуск всех пустых
полей — решение уровня формата, и молча включать его нельзя, иначе `$unset`-семантика чтения
изменится у всех разом.

## Фильтр кодируется сериализатором поля

Самое дорогое из найденного. Значение в фильтре обязано кодироваться **тем же сериализатором,
что и само поле**, иначе на сервер уходит значение другого BSON-типа:

```kotlin
// в документе лежит ObjectId
@Serializable(with = StringAsBsonObjectId::class) val shopId: String
```

Строка и `ObjectId` для MongoDB — разные типы, а не одно значение в двух видах. Условие
не совпадает никогда, запрос не падает, `deleteMany` тихо перестаёт удалять.

Внутри `filter { }` и `update { }` это решено: обе формы — и ссылка на свойство, и строковая —
берут сериализатор поля из класса.

```kotlin
collection.find { Landing::shopId eq shopId }   // уйдёт ObjectId
filter<Landing> { "_id" eq id }                 // тоже ObjectId
```

Две границы, о которых стоит знать:

* **вне области** (`"shopId" eq id` из `Filters.kt`, без `filter { }`) класса рядом нет,
  и значение кодируется по своему типу — как раньше. Если поле хранится своим типом BSON,
  собирайте значение явно: `"shopId" eq BsonObjectId.parse(id)`;
* **поле, которого класс не знает** — составной путь `"meta.author"`, поле не из модели —
  тоже кодируется по-старому. Это законный случай, поэтому он не ошибка.

Имя поля защищено отдельно: ссылка на свойство, переименованное через `@SerialName`, падает
с перечислением доступных имён, а не ищет несуществующее поле. Форма со строкой в этом случае —
не обход проверки, а штатный путь.
