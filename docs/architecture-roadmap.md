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
| `editor-search-text` | Общий matcher, Unicode ranges, вычислительные лимиты и отмена |
| `project-search` | Переносимый поиск по источникам текста, события и полнота выдачи |
| `project-search-jvm` | Обход файлов, ignore-правила, потоковое чтение и кодировки |
| `editor-session` | Документы, views, presentations, I/O и recovery coordination |
| `editor-storage-jvm` | Файловая система, атомарная запись и Linux metadata |
| `editor-diff-jgit`, `highlight-regex` | Конкретные diff и syntax providers |
| `markdown-preview` | Immutable Markdown, Math и Mermaid models и provider ports |
| `markdown-commonmark`, `markdown-math`, `markdown-mermaid` | Парсеры и поддержанные поднаборы синтаксиса |
| `tuiframe-core`, `tuiframe-jline`, `tuiframe-widgets` | Terminal runtime, cells, geometry, input, presenter и widgets |
| `tuiframe-explorer` | Файловое дерево, drag, файловые операции, история и системная корзина |
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
освободив editing session и исходную Undo history. Последующие внешние Update
имеют собственную историю frozen subject в текущем сеансе. Snapshot save использует тот же
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

## Дерево рабочей папки

`ProjectContext` принадлежит окну и владеет единственным рабочим корнем.
`SidebarState` размещает независимые палитры Files и Search в одной области.
Проводник сохраняет раскрытые каталоги, выбранную строку и прокрутку при смене
вкладки; состояние палитр включает порядок, размеры, раскрытие и подсказки.
Смена или закрытие корня не закрывает документы. В первой версии это состояние
живёт до завершения приложения; автоматического filesystem watcher нет.

Поиск по проекту использует snapshots открытых Source и дисковые источники
через отдельный JVM-адаптер. Он не создаёт editing sessions для закрытых файлов
и не занимает worker сохранения. Пакеты результатов проходят ограниченную
очередь и проверку поколения; переход проверяет версию источника и использует
обычное открытие документа и команды выделения. См.
[ADR 0008](adr/0008-project-search.md) и [руководство](project-search.md).

TUIframe предоставляет универсальные `TreeView`, `SplitPane` и `Splitter`.
`SplitPane` вычисляет непересекающиеся области и разделитель, а `Splitter`
возвращает типизированные изменения размера и gesture state через существующий
контракт pointer capture. Если минимальные размеры не помещаются, основная
область получает всё место; приложение сохраняет желаемую ширину панели.
Дерево знает плоские descriptors и операции навигации, но не `Path` или дисковые I/O.

`tuiframe-explorer` переиспользуется между picker и боковой панелью: он владеет
файловой моделью, безопасным отображением путей, фоновым сканированием и
ограниченными snapshots с проверкой generation. Профиль `READ_ONLY` разрешает
только просмотр и выбор; все popup-деревья TUInk используют его. Профиль
`MANAGE_FILES` включён только в боковой панели с закреплённым корнем. Запрет
изменений проверяется и общим сервисом операций, а не только доступностью меню.
CLI различает существующую папку и документ до входа в event loop.

Общий `Explorer` владеет жестом переноса: неподвижное удержание 500 мс с последующим движением включает
метку у указателя, подсветку назначения, раскрытие по hover и прокрутку у края.
До отпускания файловая система не меняется; допустимый drop создаёт одно
намерение Move. Pointer capture обеспечивает доставку за пределами строки;
Escape, смена поверхности и изменение геометрии отменяют жест.

`ExplorerOperations` и `ExplorerFileService` выполняют изменения и ведут
ограниченную историю текущего экземпляра. Undo/Redo проверяют identity и занятые
назначения; Undo Create удаляет только тот же пустой объект. Copy публикует
проверенную копию с диска, сохраняя исходник и bindings открытых документов.
Undo Copy проверяет всё дерево копии, а Redo — исходник и подготовленную копию;
изменённые извне данные не удаляются и не подменяются. Снимки истории ограничены
по числу элементов; слишком большая копия завершается с предупреждением без Undo.
Удаление и перенос
в Linux system Trash не отменяются и обрывают предыдущую файловую историю.
Недоступная корзина не заменяется постоянным удалением. Адаптер TUInk отвечает
за фоновые задания и применение receipt к resource bindings открытых документов,
сохраняя текст и его отдельную Undo history. Успешный собственный Save обновляет
ожидаемую identity после атомарной замены файла для перемещений и переименований;
это не разрешает Undo Copy удалить сохранённые изменения.

Source и Preview получают выделенный прямоугольник основной области; rendering,
gutter, wrapping, курсор и mouse hit-testing используют эту же геометрию.
Фокус дерева отделён от фокуса документа: необработанный ввод дерева не должен
редактировать текст. Команды меню и клавиатуры используют общую маршрутизацию;
политики диалогов и захвата указателя остаются обязательными для обеих областей.
Ctrl+Z/Ctrl+Y выбирают файловую историю только при фокусе боковой панели;
явные команды меню Explorer не зависят от фокуса Source или Preview. Уведомления
об успешном файловом Undo/Redo принадлежат панели и отключаются настройкой.
Ошибки не скрываются этой настройкой.

Подтверждённое удаление открытого файла оставляет dirty draft без resource
binding. Приложение хранит предупреждение по identity документа и показывает
общий `NoticeBanner` под Source или связанным Preview до успешного сохранения
файла либо Dismiss. Обычное редактирование и recovery checkpoint его не снимают.
Внешнее исчезновение проверяется фоновым observer, при активации, Refresh и Save;
access denied или неопределённый результат не считаются подтверждённым удалением.

Окно владеет `ExternalFileObserver`: для активного subject метаданные проверяются
примерно раз в секунду, а неизменившиеся метаданные не отменяют ограниченную
проверку содержимого примерно раз в пять секунд. Активация и Refresh запрашивают свежую
проверку. Это периодические worker reads без OS filesystem watcher. Результат
содержит subject, ожидаемый binding, generation и fingerprint конкретной версии.
Успешный собственный Save обновляет baseline и не порождает ложное предупреждение.

`ExternalDocumentNotices` хранит нерешённую версию по identity документа.
Жёлтый `NoticeBanner` под Source или Preview предоставляет Keep/Update; File menu
даёт те же команды, включая Update from disk после Keep. Клик привязан к scope
нарисованной версии, затем `ExternalChangeController` повторно читает файл на
worker. Новая версия, изменившиеся локальные данные и ошибки чтения/декодирования
сохраняют буфер и предупреждение. Обычный ввод не блокируется самим баннером.

Owner-only `acceptExternal` принимает baseline точной версии. Keep сохраняет
текст/формат, а следующий Save проверяет уже принятый baseline. Update меняет
текст и формат одной Undo записью, сохраняет прежнюю локальную историю и
историческую разницу с первоначальным открытием. Undo не откатывает принятый
дисковый baseline. Live Preview разделяет решение Source; frozen subject хранит
свои внешние Undo/Redo, а несохранённый текст участвует в close/quit и recovery.
Recovery использует текущую схему текста/формата/baseline; Undo и первоначальный
текст для исторического gutter после перезапуска не восстанавливаются.

## Проверки и границы поддержки

`./gradlew check` запускает module tests, dependency/architecture gates,
внешние Java 17 API consumers и проверки offline-документации.
Реальные keyboard, pointer, resize, history и recovery workflows проверяет
`./gradlew :app:uiRegressionTest`. Дополнительные Markdown и native probes
описаны в [руководстве по сборке](building.md),
[PTY-проверках](../verification/ui/README.md) и
[проверках переносимости](../verification/compatibility/android/README.md).

D8/R8 conversion и JVM consumers проверяют переносимые границы. Android ART,
UI, lifecycle и storage adapters требуют собственных проверок. Инструменты Git,
многокорневые workspaces и внешний plugin host пока не реализованы.
Полнота Mermaid/TeX ограничена [документированным поднабором](markdown-preview.md).
