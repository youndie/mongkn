package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document

/**
 * Индекс, который нужно создать: ключи плюс его настройки.
 *
 * Соответствует `com.mongodb.client.model.IndexModel` официального драйвера. Один из немногих
 * случаев, когда в mongkn заводится класс под набор параметров, — здесь без него не обойтись:
 * `createIndexes` принимает **список** индексов, и пара «ключи + опции» должна ехать как одно
 * значение.
 *
 * @param keys документ вида `{"поле": 1}` — `1` по возрастанию, `-1` по убыванию; значением
 *   может быть и строка (`"text"`, `"2dsphere"`, `"hashed"`).
 * @param options настройки индекса: `name`, `unique`, `sparse`, `expireAfterSeconds`,
 *   `partialFilterExpression`, `collation` и прочие ключи команды `createIndexes`.
 */
public class IndexModel(
    public val keys: Document,
    public val options: Document = BsonDocument(),
)
