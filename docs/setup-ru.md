# Перенос TUInk на другой компьютер, сборка и запуск

Для JVM-сборки используется **Java 25** через Gradle Toolchains.
Gradle можно запустить на совместимом JDK, например 17, 21 или 25.
Для сборки нативного исполняемого файла закреплён
**GraalVM Community 25.2.4 с Java 25**. Один этот дистрибутив подходит
для всех задач; `JAVA_HOME` и `GRAALVM_HOME` указывают на одну установку.

Основной пример ниже рассчитан на Linux, команды установки пакетов — на
Ubuntu/Debian. Все команды `./gradlew` выполняются из корня проекта.

## 1. Какую Java устанавливать

| Задача | Требование |
|---|---|
| Сборка исходников, тесты, запуск через Gradle | Java 25 toolchain; Gradle может работать на совместимом JDK 17, 21 или 25 |
| Сборка native-image и полного релиза | GraalVM **Community 25.2.4 / Java 25**; `JAVA_HOME` и `GRAALVM_HOME` указывают на его каталог |
| Запуск готового JVM ZIP | Java 25 или новее; Gradle и GraalVM не нужны |
| Запуск готового нативного файла | Java не нужна; должны подходить ОС, архитектура и системные библиотеки |

Для всех модулей выбран Java toolchain 25; см. [руководство по сборке](building.md).
Поставщик JVM для обычной сборки не закреплён: подходят, например, OpenJDK 25
и Eclipse Temurin 25. В CI используется Temurin 25. Устанавливайте **JDK**,
включающий `javac`, а не только JRE.

В проекте закреплён wrapper **Gradle 9.8.1**, поддерживающий запуск на Java 25.
Системный Gradle устанавливать не требуется. См.
[официальную таблицу совместимости Gradle](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime).

Рабочая схема для нативной сборки и полного релиза:

```text
JAVA_HOME = GRAALVM_HOME -> GraalVM Community 25.2.4 / Java 25
                           Gradle, компиляция, тесты, JVM-запуск и native-image
```

Установите закреплённый GraalVM из шага 3 перед нативной сборкой.

## 2. Перенести исходники

Для клонирования нужен Git (на Ubuntu/Debian: `sudo apt install git`).
На новом компьютере:

```bash
git clone https://github.com/kosatchev/tuink.git
cd tuink
```

Если работаете со своим форком, замените URL. Клонирование переносит только
опубликованные коммиты: сначала проверьте на старом компьютере `git status`
и наличие нужных коммитов в удалённом репозитории. На новом выберите ту же
ветку или тег через `git switch <ветка>` либо `git switch --detach <тег>`.

Можно скопировать каталог проекта целиком, включая скрытую `.git`, если нужна
локальная история и ветки. Это также позволяет перенести незакоммиченные
изменения. Кэши `.gradle/`, `.gradle-local/`, `.gradle-user-home/`, каталоги
`build/` в корне и модулях, `.idea/` и `.local/` для сборки не нужны.
Собственные файлы из этих каталогов при необходимости сохраните отдельно.

Обязательно сохраните `gradlew`, `gradlew.bat`, **весь каталог `gradle/`**,
`settings.gradle`, `gradle.properties`, сборочные файлы, исходники и ресурсы
всех модулей, `gradle.lockfile` каждого модуля и `apps/tuink/packaging/`. Проще переносить
весь репозиторий, чем выбирать файлы вручную. Lock-файлы и
`gradle/verification-metadata.xml` фиксируют зависимости и их контрольные суммы.

Для первой сборки на новом компьютере нужен интернет: wrapper скачивает
Gradle, затем Gradle загружает плагины и зависимости. Обычную сборку не нужно
запускать с `--write-locks` или отключать проверку зависимостей.

## 3. Подготовить Java

Для JVM-сборки установите JDK 25 любого поставщика или используйте уже
установленный совместимый JDK 17 или 21 для запуска Gradle. Укажите его
каталог в `JAVA_HOME` и проверьте `./gradlew --version`. При первом запуске
сборки Gradle при необходимости скачает toolchain Java 25; нужен доступ к
сети. Если нативная сборка не нужна, переходите к шагу 4.

### SDK для нативной сборки

На Ubuntu/Debian установите инструменты загрузки и распаковки:

```bash
sudo apt update
sudo apt install git curl ca-certificates tar unzip
```

Скачайте именно [GraalVM Community 25.2.4 / Java 25](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.2.4).
Следующие команды предназначены **только для Linux x86_64** (`uname -m`
показывает `x86_64`). Контрольная сумма закреплена также в
[документации сборки](building.md#native-toolchain).

```bash
mkdir -p "$HOME/.local/share/tuink-toolchains"
curl -fL --output /tmp/tuink-graalvm-25.2.4-linux-x64.tar.gz \
  https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/graalvm-community-jdk-25i2-25.0.4_linux-x64_bin.tar.gz

printf '%s  %s\n' \
  3f4a89de8eaa96f2ed677f09957c7e872cd8467aad3537f8b5394c1b8c4b942e \
  /tmp/tuink-graalvm-25.2.4-linux-x64.tar.gz \
  | sha256sum -c -
```

Продолжайте только если проверка завершилась с `OK`:

```bash
tar -xzf /tmp/tuink-graalvm-25.2.4-linux-x64.tar.gz \
  -C "$HOME/.local/share/tuink-toolchains"
export GRAALVM_HOME="$HOME/.local/share/tuink-toolchains/graalvm-community-25.2.4+7.1"
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

java --version
javac --version
native-image --version
./gradlew --version
```

Ожидаются Java/javac **25**, GraalVM Community **25.2.4** и Gradle **9.8.1**
с JVM **25**. Если после копирования `./gradlew` сообщает `Permission denied`,
выполните `chmod +x gradlew`.

`export` действует в текущем терминале. Для постоянной настройки добавьте
эти строки с правильным путём в конфигурацию своей оболочки, например
`~/.bashrc`. Если пользуетесь SDKMAN или другим менеджером JDK, проверьте,
что он не переключает Java после этих строк. В IDE выберите эту же Java 25
для проекта и для **Gradle JVM**: настройка IDE может отличаться от терминала.

## 4. Собрать и запустить JVM-версию

Полная проверка и сборка:

```bash
./gradlew clean check build
```

Запуск из исходников:

```bash
./gradlew --console=plain :app:run
./gradlew --console=plain :app:run -PappArgs="README.md"
```

Запускайте редактор в интерактивном терминале. Выход — `Ctrl+Q`.

Для регулярного запуска удобно один раз собрать каталог с пусковым скриптом
и всеми библиотеками:

```bash
./gradlew :app:installDist
./apps/tuink/launcher/build/install/tuink/bin/tuink --help
./apps/tuink/launcher/build/install/tuink/bin/tuink README.md
```

Пусковой скрипт работает без Gradle; после изменений исходников повторите
`installDist`. Путь с пробелами передавайте непосредственно скрипту:

```bash
./apps/tuink/launcher/build/install/tuink/bin/tuink "/путь/с пробелами/заметки.txt"
```

У `-PappArgs` в текущей сборке простое разделение по пробелам, поэтому он
не подходит для такого имени файла.

## 5. Перенести только готовую JVM-версию

На компьютере, где настроена сборка:

```bash
./gradlew :app:distZip
```

Перенесите `build/releases/tuink-<version>.zip`. Версия указана в
`gradle.properties`; вместо `<version>` в командах подставьте её значение.
На новом компьютере установите Java 25 или новее, затем:

```bash
unzip tuink-<version>.zip
./tuink-<version>/bin/tuink --help
./tuink-<version>/bin/tuink /путь/к/файлу.txt
```

Переносите весь распакованный каталог с `bin/` и `lib/`. Отдельные JAR из
`apps/tuink/launcher/build/libs/` и других модулей не являются самостоятельной поставкой:
в них нет полного набора библиотек для запуска через `java -jar`.

## 6. Собрать нативную версию для Linux

Этот вариант нужен для запуска без установленной Java. Используйте GraalVM
из шага 3 и установите зависимости C-компилятора:

```bash
sudo apt install build-essential zlib1g-dev
./gradlew :app:verifyNativeToolchain
```

Это зависимости для Ubuntu/Debian; для других систем см.
[требования Native Image](https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites).

`JAVA_HOME` и `GRAALVM_HOME` должны указывать на один и тот же абсолютный путь
к закреплённому GraalVM Community. Проверка `verifyNativeToolchain` требует
точного совпадения поставщика и версий; наличие команды `native-image` само
по себе недостаточно.

Сборка и запуск:

```bash
./gradlew :app:nativeGlibc
./apps/tuink/launcher/build/native/nativeCompile/tuink --help
./apps/tuink/launcher/build/native/nativeCompile/tuink README.md
```

Архив для переноса на другой Linux-компьютер:

```bash
./gradlew :app:nativeDistTarGz
```

Результат: `build/releases/tuink-<version>-linux-<arch>.tar.gz`.
На целевом компьютере:

```bash
tar -xzf tuink-<version>-linux-<arch>.tar.gz
./tuink-<version>-linux-<arch>/tuink /путь/к/файлу.txt
```

Подставьте имя фактически созданного архива. Нативный файл привязан к ОС и
архитектуре: Linux x86_64-файл не запускается непосредственно на Windows,
macOS или Linux ARM64. Для ARM64 нужен отдельный GraalVM под эту архитектуру
и отдельная сборка; приведённые URL и SHA-256 относятся только к x86_64.

Для текущей glibc-поставки нужны glibc 2.34 или новее и совместимая системная
zlib (`zlib1g` на Ubuntu/Debian). Сборка на другой системе может добавить
требования к более новым библиотекам, поэтому перенос на более старый Linux
проверяйте отдельно. Оставляйте
стандартный `-march=compatibility`: `-PnativeMarch=native` может сделать файл
непригодным для процессора другого компьютера.

Статическая musl-сборка (`:app:nativeMusl`) требует отдельного C-toolchain
с musl и соответствующих библиотек; установки GraalVM для неё недостаточно.
Подробности целей сборки — в [building.md](building.md#native-targets).

## 7. DEB и полный релиз

Для Debian-пакета дополнительно нужны `dpkg-deb`, `gzip`, `strip`, а для
полного релиза — ещё Python 3.10+ и POSIX-окружение с псевдотерминалом:

```bash
sudo apt install dpkg-dev gzip binutils python3
python3 --version
./gradlew :app:nativeDeb
```

Пакет находится в `build/releases/tuink_<version>_<deb-arch>.deb`.
Установка на совместимом Ubuntu/Debian:

```bash
sudo apt install ./build/releases/tuink_<version>_<deb-arch>.deb
tuink /путь/к/файлу.txt
```

Весь релиз — тесты, JVM ZIP, нативный архив, DEB, контрольные суммы и короткий
нативный бенчмарк — собирается командой:

```bash
./gradlew clean :app:release
```

Результаты будут в `build/releases/`. Эта команда использует ту же установку
GraalVM и требует всего нативного окружения; для обычной JVM-разработки
достаточно шага 4.
Упаковка и установка подробнее описаны в [packaging.md](packaging.md).

## 8. Windows, macOS и личные настройки

Для JVM-сборки на Windows установите JDK 25 и используйте PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --version
.\gradlew.bat :app:installDist
.\app\build\install\tuink\bin\tuink.bat README.md
```

Замените путь на фактический. На macOS используются `./gradlew` и Unix-скрипт
`bin/tuink`; `JAVA_HOME` должен указывать на установленный JDK 25. Текущий CI
проверяет Linux, поэтому работу терминала на Windows/macOS проверяйте отдельно.
Описанная нативная сборка и DEB-пакет рассчитаны на Linux; на Windows этот путь
можно выполнять внутри Linux-окружения WSL.

Пользовательские настройки находятся отдельно от проекта:
`~/.config/tuink/tuink.properties`. Их можно перенести вручную для той же версии
TUInk, предварительно сохранив имеющийся файл на новом компьютере.
Редактируемые документы переносятся отдельно. Перед переносом сохраните
открытые документы и закройте редактор. `~/.config/tuink/recovery/` содержит
аварийные снимки, связанные с путями файлов; копирование репозитория их не
переносит и не заменяет сохранение документов.

## 9. Если сборка или запуск не работают

| Симптом | Что проверить |
|---|---|
| Gradle падает после переключения Java; ошибка `Unsupported class file major version` | Укажите JDK 25 в `JAVA_HOME` и Gradle JVM в IDE; проверьте `./gradlew --version` и использование wrapper 9.8.1 |
| Gradle не находит Java toolchain 25 | Установите полный JDK 25; выполните `./gradlew :app:javaToolchains`. Для нестандартного пути можно передать `-Porg.gradle.java.installations.paths=/absolute/path/to/jdk-25` |
| `java -version` показывает 25, а Gradle использует другую JVM | Проверьте `JAVA_HOME`, `Gradle JVM` в IDE и `org.gradle.java.home` в `~/.gradle/gradle.properties`; при необходимости после исправления выполните `./gradlew --stop` |
| `GRAALVM_HOME must explicitly select...` | Задайте абсолютный путь к нужному GraalVM и выполните `./gradlew :app:verifyNativeToolchain` |
| `Unsupported native toolchain` | Сверьте Community/25.2.4/25.0.4 и файл `$GRAALVM_HOME/release`; другой выпуск не пройдёт проверку |
| При native-image не найдены C-компилятор или zlib | Установите `build-essential zlib1g-dev` на Ubuntu/Debian |
| Не скачиваются Gradle, плагины или зависимости | Проверьте доступ к сети и прокси; `--offline` работает только с заранее заполненным кэшем |
| Нативный файл сообщает `GLIBC_* not found` или `Exec format error` | Проверьте библиотеки, ОС и архитектуру целевой машины; используйте подходящую нативную сборку или JVM ZIP |
| Редактор сразу закрывается или не принимает клавиши | Запустите его в обычном интерактивном терминале, а не через перенаправленный stdin или консоль вывода IDE |

Все поддерживаемые команды сборки можно посмотреть через `./gradlew buildHelp`.
