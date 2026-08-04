plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    // Официальный драйвер нужен процессору только как источник сигнатур, и резолвит их KSP
    // по classpath **обрабатываемого** модуля (:mongkn-api-spec), а не этого. Здесь он стоит
    // ради констант с именами классов и чтобы версия была видна в одном месте.
    compileOnly(libs.mongodb.driver.kotlin.coroutine)
}

// jvmToolchain намеренно не фиксируется: модуль существует только на время сборки и работает
// на том JDK, что стоит у разработчика. Фиксированная 17-я на машине с JDK 25 просто роняет
// конфигурацию, а Gradle-репозиториев для скачивания тулчейнов здесь не заведено.
