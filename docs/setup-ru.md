# Перенос TUInk на другой компьютер, сборка и запуск

Для JVM-разработки используется **Java 25** любого поставщика, без требования
к patch-версии. Gradle можно запустить на совместимом JDK, например 17, 21
или 25; JDK 25 для компиляции, тестов и запуска приложения Gradle найдёт или
скачает автоматически. Готовый JVM ZIP работает на Java 25 или новее.

Для нативного релиза и необязательной PGO-сборки закреплён **Oracle GraalVM
25.0.4+7.1 с Java 25**; в этом случае `JAVA_HOME` и `GRAALVM_HOME` указывают
на одну установку.

Основной пример ниже рассчитан на Linux, команды установки пакетов — на
Ubuntu/Debian. Все команды `./gradlew` выполняются из корня проекта.

## 1. Какую Java устанавливать

| Задача | Требование |
|---|---|
| JVM-разработка и тесты | Java 25 любого поставщика; Gradle находит или скачивает toolchain автоматически и сам может работать на совместимом JDK 17, 21 или 25 |
| Native-image, нативный релиз и PGO | Oracle GraalVM **25.0.4+7.1 / Java 25**; `JAVA_HOME` и `GRAALVM_HOME` указывают на его каталог |
| Запуск готового JVM ZIP | Java 25 или новее; Gradle и GraalVM не нужны |
| Запуск готового нативного файла | Java не нужна; должны подходить ОС, архитектура и системные библиотеки |

Для всех модулей выбран Java toolchain 25; см. [руководство по сборке](building.md).
Для автоматического скачивания JDK подключён Foojay resolver. Закреплённый
Oracle SDK нужен только для нативной сборки; он включает `javac` и
`native-image` и устанавливается общим шагом в CI и release workflow.

На Linux редактор сохраняет ACL существующих файлов прямыми вызовами libc.
Пакет `acl` и отдельная библиотека `libacl` не нужны ни JVM, ни glibc, ни
статической musl-сборке. Ошибка чтения, записи или проверки ACL прерывает
сохранение и оставляет оригинал без изменений.

Статическая musl-сборка использует терминальный provider JLine `exec` и при
интерактивном запуске вызывает внешние команды `tty` и `stty`. Они должны быть
доступны в `PATH`; на Ubuntu/Debian их предоставляет пакет `coreutils`.
Это требуется и для полностью статического исполняемого файла. На минимальной
системе или при ограниченном `PATH` проверьте команды:

```bash
command -v tty stty
```

Если `tty` или `stty` недоступны, musl-сборка может сообщить
`Interactive terminal is not available (dumb terminal)` даже в настоящем TTY.
Восстановите доступ к утилитам через `PATH` и запустите редактор из
интерактивного терминала. Подробнее — в [инструкции по пакетам](packaging.md).

В проекте закреплён wrapper **Gradle 9.8.1**, поддерживающий запуск на Java 25.
Системный Gradle устанавливать не требуется. См.
[официальную таблицу совместимости Gradle](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime).

Рабочая схема для JVM-разработки:

```text
JAVA_HOME -> совместимый JDK для Gradle (например, 17, 21 или 25)
Gradle Java toolchain -> JDK 25: компиляция, тесты и JVM-запуск
```

Для нативного релиза:

```text
JAVA_HOME = GRAALVM_HOME -> Oracle GraalVM 25.0.4+7.1 / Java 25
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

Скачайте закреплённый Oracle GraalVM 25.0.4+7.1 / Java 25.
Следующие команды предназначены **только для Linux x86_64** (`uname -m`
показывает `x86_64`). Контрольная сумма закреплена также в
[документации сборки](building.md#native-toolchain).

```bash
mkdir -p "$HOME/.local/share/tuink-toolchains"
curl -fL --output /tmp/tuink-oracle-graalvm-25.0.4-linux-x64.tar.gz \
  https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.4_linux-x64_bin.tar.gz

printf '%s  %s\n' \
  76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276 \
  /tmp/tuink-oracle-graalvm-25.0.4-linux-x64.tar.gz \
  | sha256sum -c -
```

Продолжайте только если проверка завершилась с `OK`:

```bash
tar -xzf /tmp/tuink-oracle-graalvm-25.0.4-linux-x64.tar.gz \
  -C "$HOME/.local/share/tuink-toolchains"
export GRAALVM_HOME="$HOME/.local/share/tuink-toolchains/graalvm-jdk-25.0.4+7.1"
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

java --version
javac --version
native-image --version
./gradlew --version
```

Ожидаются Java/javac **25**, Oracle GraalVM **25.0.4+7.1** и Gradle **9.8.1**
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
./gradlew --console=plain :app:run --args="README.md"
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

Для имени, начинающегося с `-`, завершите список параметров отдельным `--`:

```bash
./apps/tuink/launcher/build/install/tuink/bin/tuink -- --notes.txt
```

При запуске через Gradle заключайте имя с пробелами в кавычки внутри строки
аргументов. Для имени, начинающегося с `-`, передавайте там же отдельный `--`:

```bash
./gradlew --console=plain :app:run --args='"/путь/с пробелами/заметки.txt"'
./gradlew --console=plain :app:run --args='-- "--мои заметки.txt"'
```

`-PappArgs` также поддерживает такую строку с кавычками.

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
к закреплённому Oracle GraalVM. Проверка `verifyNativeToolchain` требует
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

### Необязательная PGO-сборка

PGO — экспериментальный вариант, включаемый через `-PenablePgo`. Используйте
ту же установку Oracle SDK из шага 3 и те же C-зависимости. Дополнительно нужен
Python 3.10+ в POSIX-окружении с псевдотерминалом:

```bash
./gradlew :app:verifyNativeToolchain
./gradlew :app:nativeProfileInstrument -PenablePgo
./gradlew :app:nativeProfileTrain -PenablePgo
./gradlew :app:nativeProfileBuild -PenablePgo
./apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo README.md
```

`:app:nativeProfileOptimize -PenablePgo` объединяет свежее обучение и сборку.
Обучение запускает пять проверяемых сессий: редактирование Java/Python/YAML/Rust
и перетаскивание мышью. Профиль и его проверяемая квитанция сохраняются в
`.local/pgo/glibc/profile.iprof` и `profile.iprof.json`; они переживают `clean`
и не попадают в Git. Изменение classpath, SDK, аргументов компилятора,
`nativeMarch`, цели или harness требует нового обучения.
Другой путь задаётся через `-PpgoProfile=<путь>`;
выбирайте игнорируемый Git каталог вне `build/`, чтобы квитанция не попала
в коммит, а `clean` не удалил профиль.

По умолчанию используется glibc. Для musl добавляйте `-Ptarget=musl` ко всем
PGO-командам и установите musl toolchain; результат находится в
`apps/tuink/launcher/build/native/nativeMuslPgoOptimizedCompile/tuink-musl-pgo`.
`-Ptarget=all` для PGO запрещён: профили разных libc не смешиваются.

Ускорение зависит от корпуса и операций. Сравнивайте PGO-бинарник с обычной
нативной сборкой из того же SDK на одинаковых данных. Измеряйте CPU, время
выполнения, память и задержки отрисовки отдельно. Подробности проверки профилей:
[PGO в building.md](building.md#pgo).

Обычные архивы, DEB и полный релиз используют тот же Oracle SDK и обычный
бинарник с `-O3`. PGO-профили и оптимизированные бинарники остаются отдельными
локальными результатами сборки.

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

Для JVM-сборки на Windows установите JDK 25 любого поставщика
и используйте PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --version
.\gradlew.bat :app:installDist
.\app\build\install\tuink\bin\tuink.bat README.md
```

Замените путь на фактический. На macOS используются `./gradlew` и Unix-скрипт
`bin/tuink`; для JVM-сборки подойдёт JDK 25 любого поставщика. Текущий CI
проверяет Linux, поэтому работу терминала на Windows/macOS проверяйте отдельно.
Описанная нативная сборка и DEB-пакет рассчитаны на Linux; на Windows этот путь
можно выполнять внутри Linux-окружения WSL.

Пользовательские настройки находятся отдельно от проекта:
`~/.config/tuink/tuink.properties`. Их можно перенести вручную для той же версии
TUInk, предварительно сохранив имеющийся файл на новом компьютере.
Если задан абсолютный непустой `XDG_CONFIG_HOME`, настройки находятся в
`$XDG_CONFIG_HOME/tuink/tuink.properties`, а recovery — в
`$XDG_CONFIG_HOME/tuink/recovery/`. Пустое или относительное значение
использует каталог по умолчанию. Другой профиль автоматически не читается.
Редактируемые документы переносятся отдельно. Перед переносом сохраните
открытые документы и закройте редактор. `~/.config/tuink/recovery/` содержит
аварийные снимки, связанные с путями файлов; копирование репозитория их не
переносит и не заменяет сохранение документов.

## 9. Если сборка или запуск не работают

| Симптом | Что проверить |
|---|---|
| Gradle падает после переключения Java; ошибка `Unsupported class file major version` | Проверьте использование wrapper 9.8.1 и совместимого JDK, например 17, 21 или 25, в `JAVA_HOME` и Gradle JVM в IDE; выполните `./gradlew --version` |
| Gradle не находит Java toolchain 25 | Проверьте доступ к Foojay для автоматического скачивания или установите полный JDK 25; выполните `./gradlew :app:javaToolchains`. Для нестандартного пути можно передать `-Porg.gradle.java.installations.paths=/absolute/path/to/jdk-25` |
| `java -version` показывает 25, а Gradle использует другую JVM | Проверьте `JAVA_HOME`, `Gradle JVM` в IDE и `org.gradle.java.home` в `~/.gradle/gradle.properties`; при необходимости после исправления выполните `./gradlew --stop` |
| `GRAALVM_HOME must explicitly select...` | Задайте абсолютный путь к нужному GraalVM и выполните `./gradlew :app:verifyNativeToolchain` |
| `Unsupported native toolchain` | Сверьте Oracle GraalVM 25.0.4+7.1 / Java 25 и файл `$GRAALVM_HOME/release`; другой выпуск не пройдёт проверку |
| При native-image не найдены C-компилятор или zlib | Установите `build-essential zlib1g-dev` на Ubuntu/Debian |
| Не скачиваются Gradle, плагины или зависимости | Проверьте доступ к сети и прокси; `--offline` работает только с заранее заполненным кэшем |
| Нативный файл сообщает `GLIBC_* not found` или `Exec format error` | Проверьте библиотеки, ОС и архитектуру целевой машины; используйте подходящую нативную сборку или JVM ZIP |
| Редактор сразу закрывается или не принимает клавиши | Запустите его в обычном интерактивном терминале, а не через перенаправленный stdin или консоль вывода IDE |
| musl-сборка сообщает `dumb terminal` в настоящем терминале | Проверьте `command -v tty stty` и `PATH`; на Ubuntu/Debian установите `coreutils` |

Все поддерживаемые команды сборки можно посмотреть через `./gradlew buildHelp`.
