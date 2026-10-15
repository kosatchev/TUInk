# Architecture evolution roadmap

TUInk развивается как приложение-редактор поверх reusable terminal toolkit
**TUIframe**. Дальнейшая цель — отделить от TUI editor-session и plugin API.
Все библиотечные API пока внутренние и incubating; правила стабильности
потребуются перед их самостоятельной публикацией.

## Текущая архитектура

| Модуль | Ответственность |
|---|---|
| `unicode-text` | grapheme boundaries, UTF-16 mapping и Unicode text operations |
| `tuiframe-core` | geometry, physical events, cell metrics, line layout/cache, `Canvas`, `CellBuffer`, `Frame`, capabilities и event loop |
| `tuiframe-jline` | JLine lifecycle, decoder, raw mode, signals, ANSI presenter и cleanup |
| `tuiframe-widgets` | layout, focus, hit testing, общий захват мыши, theme и reusable widgets |
| `editor-contract` | immutable command/document DTO, argument schemas, UTF-16 ranges и `WorkspaceEdit` |
| `core` | общий документ/history, отдельные позиции views, search, editor operations и terminal-neutral `EditorTextLayout` port |
| `editor-session` | command registry, owner-thread `EditorDocumentSession`, validation и bounded async ingress |
| `render` | `EditorTextLayout` adapter, координаты/навигация представления, editor layout и render planning |
| `highlight-regex` | встроенная regex-based подсветка |
| `tui` | TUInk keymap, controllers, palettes, editor rasterization и widget adapters |
| `app` | CLI target selection, composition и release assembly |

`tuiframe-core` и `editor-contract` не имеют production dependencies на другие
project modules. `tuiframe-jline` и `tuiframe-widgets` зависят от
`tuiframe-core`. Среди project modules `core` зависит только от `unicode-text`.
`editor-session` экспортирует `editor-contract`, а `core` использует как
implementation dependency; его runtime пока включает зависимости `core`, в
том числе JGit.

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
model, metadata worker, selection/viewport state и open/save/search/recovery
flows. `FrameComposition` объединяет geometry и placements одного кадра;
заимствованные mutable editor references используются только в синхронном
render на owner thread.

`core` сохраняет terminal-neutral layout port. `render` реализует его через
`TerminalEditorTextLayout`; один adapter/cache на attached view используется editing,
layout и render planning. Editor-specific `EditorLayout`, `RenderPlan` и
`RenderRasterizer` принадлежат TUInk. Reusable painters используют clipped
`Canvas`, а editor raster hot path работает с `CellBuffer`.
`EditorViewCoordinates` объединяет hit testing, переносы, EOF и resize anchors;
`EditorViewNavigation` владеет предпочтительной визуальной колонкой и использует
логические navigation targets движка.

`EditorDocumentSession` владеет общим `EditorDocument`, публикует immutable
`DocumentSnapshot` и принимает revision-guarded `WorkspaceEdit`. У документа
одни text model, file state, history и savepoint chain. `EditorDocumentState`
хранит общие metadata, `EditorViewState` — отдельные cursor/selection/viewport,
а `EditorState` служит compatibility facade над этими двумя компонентами.
History связывает изменения текста с memento инициирующего view; derived gutter
cache и layout принадлежат отдельному view, saved/open baselines — документу.
Snapshot создаётся по запросу owner thread, а background read получает последний
опубликованный immutable snapshot. Обычный ввод не копирует весь документ после
каждой клавиши; перед запуском background work owner запрашивает текущую версию.

`TuiDocumentSession` присоединяет terminal view через внутренний
`CoreSessionBridge` и сохраняет его `TabSession` identity для recovery и Save As.
Публичность bridge нужна для внутренней границы Java-модулей; архитектурные
тесты запрещают его использование другими frontend-классами и утечку через
portable facade. Перенос всех действий в frontend-neutral typed command/effect
API остаётся следующей работой; прямые core-вызовы этого внутреннего адаптера не
являются стабильным plugin API.

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
Следующий этап — сделать session единственной portable точкой изменения editor
state для обычного редактирования, menu, recovery и внешних providers. Для
этого нужны:

- typed commands, `StateDelta`, `ChangeSet`, effects и revision-tagged events;
- effect ports для file I/O, clipboard, settings и async work;
- frontend-neutral view snapshots и управление views поверх существующего
  физического разделения document/view state;
- перенос оставшихся прямых вызовов `EditorEngine` за portable session ports
  с сохранением общей history и origin-view mementos;
- детерминированная layout/view-model preparation из state, viewport и
  decorations.

Headless commands и macro/repeat должны работать через `CommandInvocation`
без terminal input. Выделение меньшего `editor-core` позволит убрать из
session runtime ненужные зависимости существующего `core`.

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
Ближайшие приоритеты — независимый log viewer и portable session command/effect
API поверх разделённого state, затем общие registries и plugin host.
