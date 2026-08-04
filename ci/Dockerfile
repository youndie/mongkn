# Образ для сборки и прогона тестов mongkn под Linux.
#
# Существует по двум причинам. Первая: все факты ресёрча были получены на одной машине (macOS,
# mongo-c-driver 2.x), и ветка резолвера под libmongoc 1.x не исполнялась ни разу. Вторая:
# один и тот же образ используется локально и в CI, поэтому «работает у меня в контейнере»
# и «работает в CI» — одно утверждение, а не два.
#
# База с JDK: Gradle нужен, а тянуть его поверх голого debian-slim дороже, чем взять готовый.
FROM eclipse-temurin:21-jdk-noble

# libmongoc-dev в Ubuntu 24.04 — это ветка 1.x (1.26). Так и задумано: на macOS проверяется 2.x,
# здесь 1.x, и решение Р1 («поддерживаем обе через разрешение имён») перестаёт быть обещанием.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libmongoc-dev \
      libbson-dev \
      curl \
      unzip \
 && rm -rf /var/lib/apt/lists/*

# Kotlin/Native скачивает свой тулчейн в $KONAN_DATA_DIR. Выносим в отдельный каталог, чтобы
# его можно было примонтировать томом и не качать 1 ГБ на каждый прогон.
ENV KONAN_DATA_DIR=/konan
ENV GRADLE_USER_HOME=/gradle

WORKDIR /src
