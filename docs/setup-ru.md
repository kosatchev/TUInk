# Перенос TUInk на другой компьютер, сборка и запуск

Для разработки достаточно **JDK 17** и файлов проекта. Для сборки нативного
исполняемого файла дополнительно нужен **GraalVM Community 25.2.4 с Java 25**.
Это две отдельные установки Java, которые используются одновременно.

Основной пример ниже рассчитан на Linux, команды установки пакетов — на
Ubuntu/Debian. Все команды `./gradlew` выполняются из корня проекта.

## 1. Какую Java устанавливать

| Задача | Требование |
|---|---|
| Сборка исходников, тесты, запуск через Gradle | JDK 17; для простого воспроизведения окружения укажите его в `JAVA_HOME` |
| Сборка native-image и полного релиза | Дополнительно GraalVM **Community 25.2.4 / Java 25** в `GRAALVM_HOME` |
| Запуск готового JVM ZIP | Java 17 или новее; Gradle и GraalVM не нужны |
| Запуск готового нативного файла | Java не нужна; должны подходить ОС, архитектура и системные библиотеки |

Для всех модулей выбран Java toolchain 17; см. [руководство по сборке](building.md).
Поставщик JVM для обычной сборки не закреплён: подходят, например, OpenJDK 17
и Eclipse Temurin 17. В CI используется Temurin 17. Устанавливайте **JDK**,
включающий `javac`, а не только JRE.

У Gradle есть собственные ограничения: в проекте закреплён wrapper **8.14.5**.
Запуск Gradle на Java 25 поддерживается начиная с Gradle 9.1.0, поэтому
GraalVM на Java 25 не следует назначать в `JAVA_HOME` для этого проекта.
См. [официальную таблицу совместимости Gradle](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime).

Рабочая схема:

```text
JAVA_HOME    -> JDK 17                        -> Gradle, компиляция Java, тесты
GRAALVM_HOME -> GraalVM Community 25.2.4       -> native-image на Java 25
```

JDK 21 позволяет запустить текущий Gradle, но компиляция всё равно требует
отдельный JDK 17. В `settings.gradle` подключён загрузчик toolchain через
Foojay: Gradle может скачать недостающий JDK 17 при доступе к сети. Явная
установка JDK 17 заранее делает настройку проще и предсказуемее.

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

## 3. Подготовить JDK 17

На Ubuntu/Debian:

```bash
sudo apt update
sudo apt install git openjdk-17-jdk unzip
```

Пример пути для Linux x86_64; на другой системе укажите каталог своего JDK 17:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

java -version
javac -version
./gradlew --version
```

Ожидаются Java/javac 17 и Gradle 8.14.5 с JVM 17. Если после копирования
`./gradlew` сообщает `Permission denied`, выполните `chmod +x gradlew`.
Отдельно устанавливать системный Gradle не требуется.

`export` действует в текущем терминале. Для постоянной настройки добавьте
эти строки с правильным путём в конфигурацию своей оболочки, например
`~/.bashrc`. Если пользуетесь SDKMAN или другим менеджером JDK, проверьте,
что он не переключает Java после этих строк. В IDE выберите JDK 17 и для
проекта, и для **Gradle JVM**: настройка IDE может отличаться от терминала.

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
На новом компьютере установите Java 17 или новее, затем:

```bash
unzip tuink-<version>.zip
./tuink-<version>/bin/tuink --help
./tuink-<version>/bin/tuink /путь/к/файлу.txt
```

Переносите весь распакованный каталог с `bin/` и `lib/`. Отдельные JAR из
`apps/tuink/launcher/build/libs/` и других модулей не являются самостоятельной поставкой:
в них нет полного набора библиотек для запуска через `java -jar`.

## 6. Собрать нативную версию для Linux

Этот вариант нужен для запуска без установленной Java. Сначала настройте
JDK 17 из шага 3, затем установите зависимости C-компилятора:

```bash
sudo apt install build-essential zlib1g-dev curl ca-certificates
```

Это зависимости для Ubuntu/Debian; для других систем см.
[требования Native Image](https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites).

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

"$JAVA_HOME/bin/java" -version
"$GRAALVM_HOME/bin/java" --version
"$GRAALVM_HOME/bin/native-image" --version
./gradlew :app:verifyNativeToolchain
```

`JAVA_HOME` по-прежнему указывает на **JDK 17**. Не заменяйте его на
`GRAALVM_HOME`; добавлять GraalVM в `PATH` для этой сборки также не требуется.
`GRAALVM_HOME` должен быть абсолютным путём к распакованному дистрибутиву.

Проверка `verifyNativeToolchain` требует точного совпадения поставщика и
версий. Обычный OpenJDK 25, Oracle GraalVM, старый GraalVM на Java 17 и другая
версия GraalVM Community для текущей нативной сборки будут отклонены.
Наличие команды `native-image` само по себе недостаточно.

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

Результаты будут в `build/releases/`. Эта команда требует обоих JDK и всего
нативного окружения; для обычной JVM-разработки достаточно шага 4.
Упаковка и установка подробнее описаны в [packaging.md](packaging.md).

## 8. Windows, macOS и личные настройки

Для JVM-сборки на Windows установите JDK 17 и используйте PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --version
.\gradlew.bat :app:installDist
.\app\build\install\tuink\bin\tuink.bat README.md
```

Замените путь на фактический. На macOS используются `./gradlew` и Unix-скрипт
`bin/tuink`; `JAVA_HOME` должен указывать на установленный JDK 17. Текущий CI
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
| Gradle падает после переключения Java; ошибка `Unsupported class file major version` | Верните `JAVA_HOME` на JDK 17, проверьте `./gradlew --version`; текущий wrapper не поддерживает запуск на Java 25 |
| Gradle не находит Java toolchain 17 | Установите полный JDK 17; выполните `./gradlew :app:javaToolchains`. Для нестандартного пути можно передать `-Porg.gradle.java.installations.paths=/absolute/path/to/jdk-17` |
| `java -version` показывает 17, а Gradle использует другую JVM | Проверьте `JAVA_HOME`, `Gradle JVM` в IDE и `org.gradle.java.home` в `~/.gradle/gradle.properties`; при необходимости после исправления выполните `./gradlew --stop` |
| `GRAALVM_HOME must explicitly select...` | Задайте абсолютный путь к нужному GraalVM и выполните `./gradlew :app:verifyNativeToolchain` |
| `Unsupported native toolchain` | Сверьте Community/25.2.4/25.0.4 и файл `$GRAALVM_HOME/release`; другой выпуск не пройдёт проверку |
| При native-image не найдены C-компилятор или zlib | Установите `build-essential zlib1g-dev` на Ubuntu/Debian |
| Не скачиваются Gradle, плагины или зависимости | Проверьте доступ к сети и прокси; `--offline` работает только с заранее заполненным кэшем |
| Нативный файл сообщает `GLIBC_* not found` или `Exec format error` | Проверьте библиотеки, ОС и архитектуру целевой машины; используйте подходящую нативную сборку или JVM ZIP |
| Редактор сразу закрывается или не принимает клавиши | Запустите его в обычном интерактивном терминале, а не через перенаправленный stdin или консоль вывода IDE |

Все поддерживаемые команды сборки можно посмотреть через `./gradlew buildHelp`.
