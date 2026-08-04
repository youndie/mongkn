package io.github.mongkn.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Печатает нативный `MongoCollection`, снимая форму с официального корутинного драйвера.
 *
 * Процессор **читает** `com.mongodb.kotlin.client.coroutine.MongoCollection` из classpath —
 * имена методов и параметров, признак `suspend`, возвращаемые типы берутся оттуда, а не
 * зашиты здесь. Зашито другое: какие операции поддержаны ([SUPPORTED]) и во что переводятся
 * JVM-типы ([mapType]).
 *
 * Тела методов не генерируются: они делегируют в рукописный `CollectionOps`, где живёт
 * cinterop. Так опасный код остаётся под тестами и глазами, а генератор отвечает только
 * за поверхность — и её можно расширять снаружи, не трогая сгенерированное (решение Р7).
 */
internal class MongoApiGeneratorProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        generated = true

        val official = resolver.getClassDeclarationByName(resolver.getKSNameFromString(OFFICIAL_COLLECTION))
        if (official == null) {
            logger.error(
                "Не найден $OFFICIAL_COLLECTION. Официальный драйвер должен лежать на classpath " +
                    "обрабатываемого модуля — см. решение Р5 ресёрча."
            )
            return emptyList()
        }

        val mirrored = SUPPORTED.mapNotNull { operation ->
            val source = official.pickOverload(operation)
            if (source == null) {
                logger.error("В $OFFICIAL_COLLECTION нет подходящей перегрузки '$operation'")
                null
            } else {
                mirror(operation, source)
            }
        }
        if (mirrored.size != SUPPORTED.size) return emptyList()

        logger.info("mongkn: зеркалим ${mirrored.size} операций с $OFFICIAL_COLLECTION")
        FileSpec.builder(PACKAGE, "MongoCollection")
            .addFileComment(FILE_HEADER, OFFICIAL_COLLECTION)
            .addType(collectionType(mirrored))
            .build()
            .writeTo(codeGenerator, Dependencies(aggregating = false))

        return emptyList()
    }

    /**
     * Выбирает перегрузку, с которой снимается форма.
     *
     * У официального драйвера каждая операция продублирована вариантом с `ClientSession`
     * (проверено по jar 5.9.1), а `find` вдобавок имеет вариант с `Class<R>`. Берём самую
     * короткую без сессии — это и есть базовая форма.
     */
    private fun KSClassDeclaration.pickOverload(name: String): KSFunctionDeclaration? =
        getDeclaredFunctions()
            .filter { it.simpleName.asString() == name }
            .filterNot { fn -> fn.parameters.any { it.typeName().endsWith("ClientSession") } }
            .minByOrNull { it.parameters.size }

    private fun mirror(operation: String, source: KSFunctionDeclaration): FunSpec {
        val parameters = source.parameters
            .filterNot { it.isDropped() }
            .map { parameter ->
                val name = parameter.name?.asString() ?: "arg"
                ParameterSpec.builder(name, mapType(parameter.typeName(), parameter))
                    .apply { DEFAULTS[operation to name]?.let { default -> defaultValue(default) } }
                    .build()
            }

        val isSuspend = Modifier.SUSPEND in source.modifiers
        val arguments = (RUNTIME_PREFIX + parameters.map { it.name }).joinToString(", ")

        return FunSpec.builder(operation)
            .addKdoc(KDOC, operation, OFFICIAL_COLLECTION)
            .apply { if (isSuspend) addModifiers(KModifier.SUSPEND) }
            .addParameters(parameters)
            .returns(mapReturnType(source))
            .addStatement("return %T.%N(%L)", COLLECTION_OPS, operation, arguments)
            .build()
    }

    private fun collectionType(operations: List<FunSpec>): TypeSpec = TypeSpec.classBuilder("MongoCollection")
        .addKdoc(CLASS_KDOC, OFFICIAL_COLLECTION)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("client", MONGO_CLIENT)
                .addParameter("databaseName", STRING)
                .addParameter("name", STRING)
                .build()
        )
        .addProperty(
            PropertySpec.builder("client", MONGO_CLIENT)
                .addModifiers(KModifier.INTERNAL).initializer("client").build()
        )
        .addProperty(
            PropertySpec.builder("databaseName", STRING)
                .addModifiers(KModifier.INTERNAL).initializer("databaseName").build()
        )
        .addProperty(PropertySpec.builder("name", STRING).initializer("name").build())
        .addFunctions(operations)
        .build()

    /**
     * Параметры, которых в нативном API нет.
     *
     * `*Options` и `Class<R>` — про возможности, до которых прототип не дошёл; выкидываем
     * их здесь, а не молча теряем в маппинге типов, чтобы список был виден одним взглядом.
     */
    private fun KSValueParameter.isDropped(): Boolean {
        val type = typeName()
        return type.startsWith("com.mongodb.client.model.") || type == "java.lang.Class" || type == "kotlin.reflect.KClass"
    }

    private fun mapType(jvmType: String, parameter: KSValueParameter): TypeName = when {
        jvmType == BSON -> DOCUMENT
        // Параметр типа T (класс документа) — у нас документ всегда Document.
        parameter.type.resolve().declaration is KSTypeParameter -> DOCUMENT
        // Молча отображать незнакомый тип в Document нельзя: это ровно тот случай, когда
        // сгенерированный API тихо разойдётся с официальным. Пусть падает сборка.
        else -> error("mongkn-codegen: не знаю, во что переводить тип параметра '$jvmType'")
    }

    private fun mapReturnType(source: KSFunctionDeclaration): TypeName {
        val returned = source.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        return when (returned) {
            INSERT_ONE_RESULT -> OUR_INSERT_ONE_RESULT
            // FindFlow<T> реализует Flow<T> (проверено по jar 5.9.1), поэтому Flow<Document> —
            // не упрощение формы, а её подмножество: расширить до чейнинга можно потом,
            // не ломая вызывающих.
            FIND_FLOW -> FLOW.parameterizedBy(DOCUMENT)
            else -> error("Неожиданный возвращаемый тип $returned у ${source.simpleName.asString()}")
        }
    }

    private fun KSValueParameter.typeName(): String =
        type.resolve().declaration.qualifiedName?.asString() ?: type.resolve().declaration.simpleName.asString()

    private fun KSClassDeclaration.getDeclaredFunctions(): Sequence<KSFunctionDeclaration> =
        declarations.filterIsInstance<KSFunctionDeclaration>()

    private companion object {
        const val OFFICIAL_COLLECTION = "com.mongodb.kotlin.client.coroutine.MongoCollection"
        const val BSON = "org.bson.conversions.Bson"
        const val INSERT_ONE_RESULT = "com.mongodb.client.result.InsertOneResult"
        const val FIND_FLOW = "com.mongodb.kotlin.client.coroutine.FindFlow"

        const val PACKAGE = "io.github.mongkn"

        /** Операции, которые прототип поддерживает. Расширяется вместе с `CollectionOps`. */
        val SUPPORTED = listOf("insertOne", "find")

        /** Что делегирующий вызов передаёт первым — контекст операции. */
        val RUNTIME_PREFIX = listOf("client", "databaseName", "name")

        val DOCUMENT = ClassName("io.github.mongkn.bson", "Document")
        val STRING = ClassName("kotlin", "String")
        val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
        val MONGO_CLIENT = ClassName(PACKAGE, "MongoClient")
        val COLLECTION_OPS = ClassName(PACKAGE, "CollectionOps")
        val OUR_INSERT_ONE_RESULT = ClassName(PACKAGE, "InsertOneResult")

        /** Значения по умолчанию: у официального `find` фильтр тоже необязателен. */
        val DEFAULTS: Map<Pair<String, String>, CodeBlock> =
            mapOf(("find" to "filter") to CodeBlock.of("%T()", DOCUMENT))

        const val FILE_HEADER =
            "Сгенерировано mongkn-codegen. Не редактировать руками.\n" +
                "Форма снята с %L (mongodb-driver-kotlin-coroutine).\n" +
                "Реализация — в рукописном CollectionOps: cinterop генератор не печатает.\n"

        const val CLASS_KDOC =
            "Коллекция MongoDB.\n\n" +
                "Поверхность зеркалит %L: имена операций и параметров, признак `suspend`\n" +
                "и форма результата взяты из официального драйвера, а не написаны здесь.\n\n" +
                "Расширять снаружи — функциями-расширениями; править этот файл бессмысленно,\n" +
                "он перезаписывается сборкой.\n"

        const val KDOC =
            "Зеркало `%L` из %L.\n\nВся работа с C — в `CollectionOps`.\n"
    }
}
