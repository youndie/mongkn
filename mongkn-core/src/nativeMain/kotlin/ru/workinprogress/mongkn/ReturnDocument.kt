package ru.workinprogress.mongkn

/**
 * Какой документ вернуть из `findOneAnd*` — до изменения или после.
 *
 * Отдельным типом, а не `Boolean`: у официального драйвера это тоже перечисление, и
 * `findOneAndUpdate(filter, update, true)` в месте вызова не читается никак.
 */
public enum class ReturnDocument {
    /** Каким документ был до изменения. Так же ведёт себя сервер по умолчанию. */
    BEFORE,

    /** Каким стал после. */
    AFTER,
}
