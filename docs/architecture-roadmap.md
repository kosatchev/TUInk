# Architecture

TUInk объединяет переносимый движок редактирования, независимый терминальный
набор TUIframe и приложение с поддержкой Markdown. Модули сгруппированы по
владельцу в `libs/`, `apps/` и `verification/`; стабильные Gradle project paths
заданы в `settings.gradle`. [Карта репозитория](../README.md#repository-layout)
сопоставляет эти имена с физическими каталогами.

## Модули и зависимости

| Группа | Ответственность |
|---|---|
| `unicode-text` | Независимые Unicode и grapheme primitives |
| `editor-contract` | Документные snapshots, команды, storage и provider ports |
| `core` | Текст, история, поиск, формат и правила редактирования |
| `editor-session` | Документы, views, presentations, I/O и recovery coordination |
| `editor-storage-jvm` | Файловая система, атомарная запись и Linux metadata |
| `editor-diff-jgit`, `highlight-regex` | Конкретные diff и syntax providers |
| `markdown-preview` | Immutable Markdown, Math и Mermaid models и provider ports |
| `markdown-commonmark`, `markdown-math`, `markdown-mermaid` | Парсеры и поддержанные поднаборы синтаксиса |
| `tuiframe-core`, `tuiframe-jline`, `tuiframe-widgets` | Terminal runtime, cells, geometry, input, presenter и widgets |
| `render` | Терминальная геометрия редактора и Markdown, подготовленные строки и render plans |
| `tui` | Окна, focus, команды, настройки и платформенные адаптеры приложения |
| `app` | CLI entry point, launchers и упаковка |

Зависимости направлены к контрактам и переносимым моделям. Engine и session
не зависят от терминала, Markdown или JVM filesystem adapter. CommonMark types
остаются внутри своего адаптера. TUIframe не знает документов, правил
редактирования и прикладных команд TUInk.

JVM-приложение использует Java 25. Переносимые editor и Markdown modules
сохраняют Java 17 API/bytecode; это само по себе не означает готовность
Android frontend. Библиотечные Java API пока внутренние и incubating.
Storage и installation formats имеют отдельную
[политику совместимости](compatibility.md).

## Владение документом и результатами работы

Mutable text, история и savepoint принадлежат document backend за
`EditorDocumentSession`. Views хранят независимые cursor и selection.
`EditorWorkspace` владеет одним реестром presentations, их порядком, группами
и выбранным представлением; frontend использует проекции этого реестра.

Source и Preview образуют связанные presentations. Preview получает immutable
snapshot, собственные selection и viewport. После закрытия Source он может
сохранить `FrozenDocumentSource` с точным текстом, форматом и resource binding,
освободив editing session и Undo history. Snapshot save использует тот же
`DocumentIoCoordinator`, что и живой документ, через `FrozenDocumentSession`.
Подробности описаны в [ADR владения парой](adr/0007-paired-markdown-presentations.md).

Фоновая операция получает immutable input и явного владельца. Результат
проверяется по document/presentation identity, revision, generation и lifetime
до публикации. Выбранная в момент завершения вкладка не становится новым
получателем. Подготовка Save, inspection, overwrite approval и publication
имеют отдельные фазы; неопределённый исход сохраняет документ или снимок.

## Ввод и отрисовка

Decoder выдаёт физические события. Keymap, focus и command registry выбирают
единственное намерение и получателя: Command, Text, Gesture или Consumed.
После команды исходный клик или клавиша повторно не исполняются. Поля,
меню, модальные диалоги и pointer capture используют общие политики.
См. [контракт ввода](shared-engine-input-contract.md) и
[контракт владельцев](shared-engine-interaction-design.md).

Source и Preview используют общие text interaction, scrolling и prepared-row
primitives через собственные geometry/content adapters. Подготовленные строки
хранят стабильные текст и стили; selection, search и caret накладываются при
рисовании. Кэши учитывают удерживаемую память, включая данные, ещё принадлежащие
кадру после вытеснения. Отказ admission допускает ограниченный streaming path.

Terminal cells, ANSI, Unicode cell widths и window geometry остаются в
терминальных модулях. Portable models используют semantic styles и позиции
в zero-based UTF-16 offsets. Подробные решения находятся в [ADR](adr/).

## Проверки и границы поддержки

`./gradlew check` запускает module tests, dependency/architecture gates,
внешние Java 17 API consumers и проверки offline-документации.
Реальные keyboard, pointer, resize, history и recovery workflows проверяет
`./gradlew :app:uiRegressionTest`. Дополнительные Markdown и native probes
описаны в [руководстве по сборке](building.md),
[PTY-проверках](../verification/ui/README.md) и
[проверках переносимости](../verification/compatibility/android/README.md).

D8/R8 conversion и JVM consumers проверяют переносимые границы. Android ART,
UI, lifecycle и storage adapters требуют собственных проверок. Постоянное
дерево рабочей папки, инструменты Git и внешний plugin host пока не реализованы.
Полнота Mermaid/TeX ограничена [документированным поднабором](markdown-preview.md).
