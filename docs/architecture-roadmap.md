# Architecture evolution roadmap

TUInk развивается как приложение-редактор поверх reusable terminal toolkit
**TUIframe**. Дальнейшая цель — отделить от TUI editor-session и plugin API.
Общий движок также должен обслуживать будущие Android и Windows clients.
Все библиотечные API пока внутренние и incubating; правила стабильности
потребуются перед их самостоятельной публикацией.

## Текущая архитектура

| Модуль | Ответственность |
|---|---|
| `unicode-text` | grapheme boundaries, UTF-16 mapping и Unicode text operations |
| `tuiframe-core` | geometry, physical events, cell metrics, line layout/cache, `Canvas`, `CellBuffer`, `Frame`, capabilities и event loop |
| `tuiframe-jline` | JLine lifecycle, decoder, raw mode, signals, ANSI presenter и cleanup |
| `tuiframe-widgets` | layout, focus, hit testing, общий захват мыши, theme и reusable widgets |
| `editor-contract` | immutable command/document DTO и блочный текст, UTF-16 ranges, `WorkspaceEdit`, storage/resource/recovery contracts, semantic highlighting/diff ports и общий каталог языков |
| `core` | общий документ/history, отдельные позиции views, search, editor operations и terminal-neutral `EditorTextLayout` port |
| `editor-session` | command registry, owner-thread `EditorDocumentSession` и `EditorWorkspace`, document/view lifetime, validation, I/O/recovery coordinators и bounded async ingress |
| `editor-diff-jgit` | опциональный bounded JGit diff; явно подключается к terminal view |
| `editor-storage-jvm` | JVM filesystem adapter: paths, версии, безопасная публикация файлов, permissions и Linux FFM xattr/ACL |
| `render` | `EditorTextLayout` adapter, координаты/навигация представления, editor layout и render planning |
| `highlight-regex` | встроенная bounded regex-based подсветка с semantic token output, без зависимости от terminal render |
| `tui` | TUInk keymap, controllers, palettes, editor rasterization и widget adapters |
| `app` | CLI target selection, composition и release assembly |

`tuiframe-core` и `editor-contract` не имеют production dependencies на другие
project modules. `tuiframe-jline` и `tuiframe-widgets` зависят от
`tuiframe-core`. Среди project modules `core` зависит от `unicode-text` и
нейтрального `editor-contract`: редактирование и подсветка используют одни имена
и расширения, сохраняя собственные правила обработки языка.
`editor-session` экспортирует `editor-contract`, а `core` использует как
implementation dependency. Их production runtime не включает JGit, FFM provider
или TUIframe. `core` содержит stream-based `DocumentCodec`, но не обращается к
filesystem. `editor-contract` задаёт opaque resource/version, format, capabilities
и load/save receipts. `editor-session` предоставляет одноразовые detached I/O
tasks, owner approval/publication и recovery coordination без типов `core` в
публичном API. Ошибки encoding и неопределённой записи принадлежат storage
контракту. Filesystem adapter подключается явно в TUI. Android `Uri` adapter
ещё не реализован.

Production TUI выполняет document commands через тот же `EditorDocumentSession`,
что headless consumer. Нейтральный backend port скрывает mutable core; frontend
создаёт и сохраняет собственный terminal view для render/geometry. Общие
I/O/recovery coordinators используются TUI и headless consumer. `EditorWorkspace`
владеет ordered membership документов/views и выбором активного view; TUI получает
из него read-only проекцию вкладок. Android/Windows
clients, ART, IME и платформенные storage adapters остаются отдельными работами.

Общие модули публикуют Java 17 bytecode/API и Gradle variants; compiler и desktop
integration tests используют JDK 25. JVM storage adapter требует Java 25.
Проверка внешнего consumer описана в
[Android compatibility probe](../verification/compatibility/android/README.md): компиляция
DEX не заменяет ART, IME, lifecycle и platform-storage проверки.

TUIframe использует namespace `dev.tuiframe.*` и не зависит от TUInk, editor или
plugin model. `TuiApplicationRunner` управляет event loop и порядком кадров;
`EditorWindowController` выполняет application maintenance, input routing и
composition через настоящий event-loop handler. `TerminalSession` владеет terminal resources и
восстанавливает их после выхода, EOF, interrupt и исключений.

Widgets получают presentation-only данные и immutable placements. Один
placement задаёт рисование, hit testing, focus routing и cursor geometry.
`FocusManager` управляет focus в переданном приложением `FocusScope`.
`PointerCaptureManager` хранит только стабильный адрес surface/scope/target,
а текущий placement и результат отмены получает из переданного snapshot.
TUInk связывает разные типы scopes через `WindowPointerCapture.Frame` одного
события; callbacks и placements не сохраняются менеджером между кадрами.
`Button`, `Modal`, `TextField`, `ListView`, `TabBar`, `MenuBar` и `TreeView`
остаются независимыми от editor state и filesystem. TUInk владеет file tree
model, metadata worker, selection/viewport state и представление
open/save/search/recovery flows. Общий протокол I/O и recovery lifecycle
принадлежит `editor-session`. `FrameComposition` объединяет geometry и placements одного кадра;
заимствованные mutable editor references используются только в синхронном
render на owner thread. Полные placements файлового диалога и справки готовятся
до рисования и используются input, focus, pointer capture, cursor и paint.
При изменении состояния или размеров готовится новая проекция; paint не
пересчитывает layout и не принимает новое состояние прокрутки. Последний
видимый help viewport сохраняет anchor при временном переходе в compact mode.

Прикладной поиск исполняет `SearchCommandHandler` с узким контекстом документа
и вкладок. Его результат различает число совпадений и изменение документа;
окно применяет viewport и notification effects. `SearchSelection` разделяет
ограниченную автоподстановку Ctrl+F, автоматическую подсветку и точную проверку
текущего совпадения. `SearchPresentation` владеет count/tab-marker caches;
ключи учитывают identity модели и revision. Поля, параметры, caches и focus
удерживает `TabUiState` своего `ViewId`; обычная смена вкладки сохраняет поиск.
Переход к результату в другой вкладке копирует значения поиска в её независимые
controls, сохраняя исходную вкладку. Замена по-прежнему действует на активный документ.
Literal-поиск в `SearchMatcher` использует KMP по Unicode code points:
подготовка запроса и проход строки линейны, результаты сохраняют исходные
UTF-16 offsets. Case-insensitive режим сравнивает code points после
`Character.toLowerCase(Character.toUpperCase(cp))`, без нормализации текста
и многосимвольных замен.

`SaveWorkflow` связывает save/Save As/overwrite с `UiModalState`,
`SaveContinuation` и `SaveAsSuggestionStore`. `SaveTargetPolicy` задаёт
предложения пути и сообщения; quit/close continuations остаются во frontend.
Единственный публичный save API session — сохранённый `DocumentIoCoordinator`:
owner capture, detached inspection, approval точного target/version, detached
write и owner publication. Executor принадлежит host. Session блокирует изменение
документа во всех save phases; navigation, selection и copy остаются доступны.
Window применяет presentation effects и обновляет highlighter исходной вкладки.
Save/close continuations адресуют исходный `ViewId`; индексы вычисляются заново
для текущего отображения и не служат identity фонового результата.
`PreparedDocumentOpen` допускает один detached read и owner admission точного
результата. `FilePickerOpenWorkflow` проверяет workspace aliases и создаёт
представление после admission; отменённый read не публикует вкладку. Обычный
CLI open по-прежнему допускает создание нового документа.

`RecoveryCoordinator` владеет checkpoint scheduling, entry ownership,
подготовленным adoption восстановленных drafts и разрешением cleanup.
`RecoveryPersistence` и executor задаются host. `prepareRecovery` захватывает
immutable document inputs на owner, background materialization выполняется на worker;
TUI отдельно добавляет свой viewport в существующий recovery wire format.
Одновременно удерживается одна checkpoint operation; изменения во время записи
попадают в следующую версию. Незавершённый или неопределённый document save
сохраняет draft и recovery. Detach view не даёт разрешения удалить checkpoint.
Несколько views одного документа используют одного retained recovery participant;
после закрытия исходного view checkpoint захватывает позицию оставшегося view,
сохраняя document/entry identity.

`core` сохраняет terminal-neutral layout port. `render` реализует его через
`TerminalEditorTextLayout`; один adapter/cache на attached view используется editing,
layout и render planning. Editor-specific `EditorLayout`, `RenderPlan` и
`RenderRasterizer` принадлежат TUInk. Reusable painters используют clipped
`Canvas`, а editor raster hot path работает с `CellBuffer`.
`EditorViewCoordinates` объединяет hit testing, переносы, EOF и resize anchors;
`EditorViewNavigation` владеет предпочтительной визуальной колонкой и использует
логические navigation targets движка.
`TerminalTextLayoutCache` удерживает компактные индексы числа visual rows для
четырёх вариантов переноса; списки offsets создаются по запросу и вытесняются
по общему бюджету числа записей и offsets. Построение индекса не заполняет
кэш раскладками всех невидимых строк документа. Подсчёт rows и получение
offsets используют один обход `TerminalLineLayout`; подсчёт не создаёт список
offsets для каждой строки.

`IndentationDecisions` и `MarkdownListDecisions` вычисляют typing assists через
read-only доступ к строкам и необходимые настройки/проекции колонок. Они не
владеют mutable document или view state. Engine сохраняет фазы вызова правил,
применение правок, cursor/selection и Undo grouping; literal paste проходит
отдельным путём. Контекст Markdown различает списки, отступные блоки кода и
fences внутри списков. Контекст запрашивается только для подходящего маркера
списка. Engine хранит ограниченный набор контрольных точек разбора и состояние
перед последней запрошенной строкой. Первый дальний запрос последовательно
разбирает префикс без сохранения объекта для каждой строки; последующие запросы
используют ближайшее действительное состояние. Правки сбрасывают состояния
после первой изменённой строки, замена документа сбрасывает весь кэш.
Markdown использует табстопы через четыре колонки независимо
от настройки ширины табуляции для отображения. Ограничения lookback для эвристик
отступов и консервативные отказы сохранены.
В `highlight-regex` один разбор границ fenced blocks обслуживает подсветку и
ограниченный lexical scanner, включая fences внутри списков. Разбор остаётся
владением highlighter и не добавляет зависимость production-кода от `core`.

Законченную локальную замену текста выполняет `TextModel.replaceRange`.
Внутренний immutable `ReplacementDelta` содержит текстовую операцию и только
затронутые соседние EOL slices. Engine хранит эти deltas в компактной истории;
модель проверяет ожидаемые текст и разделители до replay и точно восстанавливает
их при Undo/Redo. Нормализация EOL выполняется после полной замены и остаётся
внутренним делом модели. Bulk edits сохраняют отдельный snapshot path.
Массовая замена последовательно получает диапазоны от `SearchMatcher` и собирает
результат без списка объектов на каждое совпадение; весь результат применяется
одним шагом истории. Совпадения с идентичной заменой учитываются в счётчике,
но не меняют документ, savepoint и историю. Immutable EOL metadata хранится
сбалансированным деревом серий LF/CR/CRLF. Локальные вставки, удаления и
нормализация разделителей разделяют неизменившиеся поддеревья с прежней версией;
полный обход сохранения и recovery использует линейные итераторы.

Обычный ввод, Enter, удаление и cut выполняют typing assists поверх локального
overlay строк. До публикации готовятся history, view state и необходимая ёмкость
встроенного буфера; отказ оставляет исходный документ и историю целыми.
Публикация меняет уже подготовленные ссылки и не копирует нетронутые строки.
Другие стратегии хранения используют независимый sibling buffer, если явно
не поддерживают подготовленную публикацию.

`EditorDocumentSession` управляет общим документом через нейтральный backend, публикует immutable
`DocumentSnapshot` и принимает revision-guarded `WorkspaceEdit`. У документа
одни text model, file state, history и savepoint chain. `EditorDocumentState`
хранит общие metadata, `EditorViewState` — отдельные cursor/selection/viewport,
а `EditorState` служит compatibility facade над этими двумя компонентами.
History связывает изменения текста с memento инициирующего view; derived gutter
cache и layout принадлежат отдельному view, saved/open baselines — документу.
Snapshot создаётся по запросу owner thread, а background read получает последний
опубликованный immutable snapshot. Обычный ввод не копирует весь документ после
каждой клавиши; перед запуском background work owner запрашивает текущую версию.

`TuiDocumentSession` создаёт terminal view и подключает его нейтральный
`DocumentBackend` к session. Его `TabSession` identity сохраняется для recovery
и Save As. Core objects остаются внутри composition/render, document commands
выполняются только через session. Архитектурные тесты запрещают frontend обходить
эту границу и проверяют публичный API без исключения для mutable bridge.
`EditorWorkspace` — единственный владелец membership и selection. Один session
не может одновременно принадлежать двум workspaces. Добавление подготовленного
набора views валидируется и выделяет память до публикации; удалённый `ViewId`
навсегда перестаёт принимать команды. Последний view документа остаётся в
workspace до завершения его save operation; удаление другого view не меняет
адресата save receipt. Текущие host/backend ports ещё не являются стабильным plugin API.

Владение общими DTO описывает [ADR 0001](adr/0001-editor-contract-ownership.md),
разделение document/session и frontend view state —
[ADR 0002](adr/0002-session-and-frontend-view-state.md), async/failure model —
[ADR 0003](adr/0003-async-and-plugin-failure-model.md), сохранение file metadata —
[ADR 0004](adr/0004-document-file-fidelity.md), focus/input/capture —
[ADR 0005](adr/0005-dialog-input-and-focus.md).

## Архитектурные границы

- Decoder выдаёт physical events. TUInk keymap и focus routing разрешают
  `CommandInvocation`; keyboard и menu используют один registry path.
  `CommandRecipient` выбирает semantic получателя, `ControlCommandRouter`
  приводит локальные команды к общему input path контролов. Viewport применяет
  policy из результата исполнения, включая отклонённые команды.
- Mutable editor state принадлежит session/engine thread. `WorkspaceEdit`
  проходит полную проверку document ID, revision, диапазонов и surrogate
  boundaries до atomic mutation с одной undo-операцией. Text ranges используют
  zero-based UTF-16 offsets и half-open boundaries.
- Background consumers получают immutable snapshots. Async-результат содержит
  document ID и revision; foreign или stale результаты отвергаются.
  Bounded ingress и ограниченный batch на turn сохраняют порядок и fairness.
- TUIframe предоставляет события, terminal runtime и рисование. Editor commands,
  file flows, palettes и plugin policy принадлежат приложению.
- Документ, дерево, список и справка используют общие `Scrollbar` и
  `ScrollInteraction`; `ScrollLayout` рассчитывает полосы и закреплённые колонки.
  Полосы наследуют фон родительской поверхности и стиль её рамки через
  `ScrollStyle.fromParent`. Справка использует `ListView.wrappedText` с переносом
  строк и сохранением логической позиции при изменении ширины окна.
- Portable plugin API использует semantic styles, ranges, diagnostics и
  declarative contributions. Terminal colors, cells, `Canvas`, widgets и
  terminal events остаются во frontend adapter.
- In-process plugins считаются доверенным кодом с правами процесса. Deadline и
  cooperative cancellation ограничивают ожидание и применение результата;
  hard isolation требует отдельного процесса и versioned protocol.
- Storage/install formats, TUIframe Java API, plugin `apiVersion` и возможный
  process protocol имеют независимые версии и policies. Текущая
  [compatibility policy](compatibility.md) управляет storage и installation.
- Public SPI расширяется через default methods или capability interfaces,
  сохраняя совместимость существующих implementations.

## Оставшаяся работа

### Проверка TUIframe вторым приложением

Независимый `examples/log-viewer` должен использовать только API
`dev.tuiframe.*` из `tuiframe-core`, `tuiframe-jline` и `tuiframe-widgets`.
Он проверит toolkit без editor core или готовой filesystem model:

- async stream, bounded ingress, pause/resume и виртуализованный список;
- filter `TextField`, status bar, scrollbar и help/settings modal;
- keyboard, mouse, resize, Unicode, wide/control text и длинные строки;
- tiny terminal, monochrome, color capability fallback и alt-screen on/off;
- cleanup после normal exit и injected exception.

PTY coverage требуется как минимум для `80x24` и tiny terminal. Перед public
alpha пример должен собираться отдельной nested build по Maven coordinates
`dev.tuiframe:tuiframe-core`, `dev.tuiframe:tuiframe-jline` и
`dev.tuiframe:tuiframe-widgets`. Это проверит POM, transitives и отсутствие
зависимостей от TUInk, editor, plugins или internal packages.

Потребности второго consumer определят дальнейшее расширение widgets:
horizontal list scrolling, custom rows и finer render invalidation.
Capability mapping и terminal matrix также требуют проверки вне TUInk.

### Editor-session и frontend view state

Общий документ и независимые core views уже разделены, TUInk подключён к
`EditorDocumentSession`, а viewport и навигация принадлежат представлению.
Обычный ввод, paste, clipboard, Undo/Redo, logical navigation, pointer selection
и search/replace уже проходят через типизированные session commands. Target
содержит `DocumentId`, стабильный `ViewId`, версии текста, состояния документа и
положения view; смена формата или savepoint тоже делает старый target неактуальным;
устаревшие и foreign-owner вызовы отвергаются до mutation. Небольшой результат
готовится до commit, full snapshot публикуется отдельно по запросу. Backend
исполняет команду через engine исходного view, сохраняя coalescing и origin Undo.
Общие open/save/recovery protocols уже вынесены из TUI. Для дальнейшего
разделения application и frontend state нужны:

- `StateDelta`, `ChangeSet`, effects и revision-tagged events для application coordination;
- effect ports для clipboard, settings и остальных application effects;
- отдельные платформенные lifecycle/IME правила поверх общего lifetime views;
- детерминированная layout/view-model preparation из state, viewport и
  decorations.

Headless commands и macro/repeat должны работать через `CommandInvocation`
без terminal input. Выделение меньшего `editor-core` позволит убрать из
session runtime ненужные зависимости существующего `core`.

### Workspace и platform adapters

`EditorWorkspace<T>` хранит ordered document/view membership и выбранный `ViewId`.
`T` — принадлежащая frontend проекция с layout, renderer и viewport controllers.
`SessionContext` не содержит собственного mutable списка вкладок, active index
или сохранённых model/state/engine: они выводятся из selection владельца.
`TabUiState` удерживает поиск, диалоги, поля ввода, picker и help state,
notifications и focus managers каждого frontend view. Окно готовит layout и
presentation effects выбранной вкладки; смена view отменяет pointer capture
и пересоздаёт placements. Только quit confirmation и startup recovery остаются
общими modal приложения. Переключение вкладок доступно до маршрутизации локального
блокирующего ввода. Значки `?` и `/` в TabBar и Tab menu выводятся из сохранённого
состояния диалога и поиска, включая неактивные вкладки.
Open/save/close результаты адресуют документы и views стабильными IDs; удаление
вкладки не перенаправляет завершение операции на занявший её индекс документ.
Результат, завершившийся после перехода пользователя в другую вкладку, обновляет
UI исходного view и не переключает выбранную вкладку.
Вкладочные диалоги сохраняют один общий file worker с одной I/O operation in-flight;
session save-lock остаётся общим для всех views одного документа.

Проверки aliases, physical target, read-only и существования имени находятся в
JVM adapter `JvmFileAdvisory`, без `Path` в workspace API. Это advisory host
проверки: они частично исполняются синхронно на owner thread, не резервируют имя
и не дают права перезаписи. Save approval по-прежнему связывается с detached
inspection конкретного target/version. Отдельный detached lookup/suggestion
admission остаётся возможной работой по задержкам файлового диалога.

При временном отсоединении view host сохраняет document participant и запрашивает
lifetime checkpoint. Исключение документа с `PRESERVE` сохраняет уже записанные
draft entries; оно не заменяет capture последних правок перед suspend.
Workspace retirement отдельного view сохраняет документ, пока у него есть другой
view. Последний view нельзя убрать при незавершённом save. Окончательное удаление
документа по-прежнему требует выбранного host решения о recovery; общий owner
сам по себе не обещает сохранение перед Android suspend.

Android storage/lifecycle/IME adapter и Windows frontend пока отложены. Общий
Java 17 API и внешние JVM/D8/R8 пробы проверяют переносимую границу; Android ART,
реальные provider grants, suspend/process death и Windows filesystem/UI поведение
потребуют собственных runtime проверок при появлении соответствующих adapters.

### Registries, built-ins и semantic presentation

Command, keybinding, menu, settings, language/file association и decoration
contributions должны использовать общие registries с namespaced IDs.
Built-ins и внешние extensions проходят одинаковые validation и dispatch
paths; duplicate IDs и invalid contributions отвергаются до mutation.

Regex highlighter должен стать built-in provider. Semantic
`DecorationStyleKey` требует registry, fallback и единого порядка layers;
frontend переводит semantic styles в доступную terminal palette, включая
16/256/truecolor capabilities. Repeat и безопасные edit macros записывают
command invocations.

Status и gutter contributions остаются data descriptors. Arbitrary panels и
forms потребуют frontend-neutral declarative contribution model. Возможный
TUI-only SPI должен быть отдельным opt-in контрактом с clipped drawing без
доступа к terminal lifecycle или screen ownership.

### Plugin API и host

Будущий `plugin-api` зависит от `editor-contract` и использует его DTO, ranges
и edits. Минимальный контракт включает descriptor с `id`, `version`,
`apiVersion`, entrypoint и required capabilities; command, language,
decoration и diagnostics providers; declarative menu/keybinding/status/gutter
contributions; deadlines и cancellation. Completion добавляется после
стабилизации async provider contract. Lifecycle и editor events используют
узкие typed subscriptions с явными thread и ordering semantics.

`plugin-host` отвечает за manifest/dependency validation, registry ownership,
unload cleanup, bounded background execution, deadlines, cancellation,
stale-result rejection и передачу проверенных результатов в `editor-session`.
Exceptions отключают или ограничивают ошибочный provider. Несовместимый
`apiVersion` отвергается до исполнения plugin code.

Сначала общий SPI используется built-ins, затем bundled plugins через
`ServiceLoader`. Dynamic JVM JAR loader с отдельным classloader требует
стабильных API и lifecycle. Для native требуется отдельное решение: bundled
plugins с build-time composition либо versioned out-of-process protocol.
Dynamic Java loading внутри native image не входит в поддерживаемую модель.

Conformance fixtures должны включать command/decorations plugin и async
provider с timeout, exception, overload, cancellation и stale-result cases.
Timeout прекращает ожидание и применение результата; host учитывает, что
non-cooperative in-process Java code может продолжить работу.

### Публикация и стабильность API

Для public alpha TUIframe нужны два consumers, external-consumer build,
lifecycle/threading/render/capability documentation, API baseline,
cross-platform JVM tests и PTY conformance. Published dependencies не должны
включать editor, JGit или highlighter.

Для public alpha plugin API нужны built-ins и external fixtures на одном
контракте, failure/compatibility tests, документированные JVM loader и native
возможности. Target dependency direction:

```text
builtin/external plugins -> plugin-api -> editor-contract
plugin-host -> plugin-api + editor-session
editor-session -> editor-contract + editor-core -> unicode-text
tui -> tuiframe-widgets + tuiframe-jline + editor-session + plugin-host
app -> tui + builtin plugins
```

`editor-core`, `plugin-api` и `plugin-host` на этой схеме — планируемые модули.
Для `1.0` TUIframe должен пройти минимум две minor-версии с двумя consumers.
Также требуются автоматический public API diff, независимые версии приложения,
TUIframe и plugin API, SemVer/deprecation policy, source/javadoc artifacts и
полные POM metadata. Отдельный репозиторий TUIframe имеет смысл после public
alpha и появления независимого consumer.

## Проверка архитектуры

Текущие команды тестирования и dependency checks приведены в
[руководстве по сборке](building.md); методика измерения render path — в
[руководстве по profiling](profiling.md).

`EditingWorkflowArchitectureTest` запрещает typing rules зависеть от mutable
model/engine/view owners, а прикладным workflows — от `TerminalEditorApp` и
контролов TUIframe. Поведение проверяется через существующие engine/window
регрессии, контракты обратимых замен с mixed EOL и PTY-сценарии.

По мере появления consumers и plugin host проверки должны охватывать:

- geometry, clipping, input fragments, focus, capabilities и Unicode/wide-cell
  invariants, включая property/fuzz resize/input sequences;
- reference-terminal replay и fake-terminal cleanup при EOF и exceptions;
- PTY keyboard/mouse/resize/modal scenarios в TUInk и log viewer;
- external-consumer builds и отсутствие запрещённых project dependencies;
- plugin validation, deterministic ordering, stale results, failure handling,
  classloader/resource cleanup и compatibility с предыдущим поддерживаемым API;
- release artifacts, source/javadoc/POM metadata и API diff;
- emitted bytes, changed rows/cells, allocations, frame latency и queue time.

Terminal conformance определяется конечным экраном, корректными событиями и
cleanup; конкретные ANSI bytes могут меняться при улучшении presenter.
Ближайшие приоритеты editor — remaining application effects и отдельные
платформенные adapters/lifecycle, затем общие registries и plugin host. Независимый log viewer остаётся
отдельной проверкой TUIframe.
