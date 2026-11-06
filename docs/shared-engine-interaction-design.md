# Владение состоянием и маршрутизация взаимодействия представлений

Контракт владения документами, представлениями и результатами фоновой работы
для Source и Markdown Preview.

## Владельцы состояния

| Состояние | Единственный владелец | Доступ остальных компонентов |
|---|---|---|
| Исходный текст, revision, история и savepoint | Document backend через `EditorDocumentSession` | Document commands и согласованные snapshots |
| Логический cursor/selection Source | Editing view сессии | `ViewSnapshot`; изменение через `MoveTo`/`Select` и существующие document commands |
| Порядок presentations, группы, membership, selection | `EditorWorkspace` | Проекции единого реестра; UI не ведёт второй список |
| Preview semantic selection и visual affinity | Presentation state конкретного Preview | Общий interaction controller вычисляет переход по переданному состоянию |
| Viewport и preferred visual column | Состояние frontend view | Geometry/content adapters; без второй копии логического cursor Source |
| Текст, cursor и выделение поля | Существующий widget state поля | Общий field dispatcher и placements |
| Focus и presentation-local modal state | `TabUiState` адресуемого представления | Focus scope и подготовленные placements |
| Quit confirmation и startup recovery | Окно приложения | Общие workflow и window-modal scope |
| Pointer capture | `WindowPointerCapture` и widget capture manager | Стабильный scope/target; актуальные placements передаются на событие |
| Parse/layout tasks, отмена и bounded caches | Preview service и владельцы принятых layout | Immutable results, identity/revision/generation/token validation |
| Save inspection, authorization, publication | `DocumentIoCoordinator` выбранного save subject | Detached tasks и receipts; UI применяет адресованные effects |
| Preferences | Общая модель настроек и существующий settings writer | Вычисленные параметры поверхности; focus/capabilities не записываются в профиль |

Операция адресует исходные document/presentation IDs и поколение.
Выбранная в момент завершения вкладка не становится её новым получателем.
Смена Source/Preview и открытие диалога не меняют владельца документа.

## Конечный результат маршрутизации

Один физический ввод заканчивается одним результатом: Consumed, Command,
Text или Gesture с определённым получателем. Команда содержит invocation
с аргументами и результат единственного разрешения registry. Исполняемое
исходное событие вместе с ней не сохраняется.

Неизвестная, недоступная или отклонённая команда завершает обработку.
Промежуточный Unhandled разрешён внутри router chain, но не запускает
повторную интерпретацию уже разрешённой команды. Resize, lifecycle и
завершения detached operations имеют отдельный канал.

## Правила выбора получателя

Области образуют иерархию с существующими modal/focus policies:

1. Системное событие обрабатывает владелец lifecycle или геометрии окна.
2. Актуальный capture получает продолжение своего жеста, если его scope
   остаётся разрешённым текущей модальной политикой.
3. Активная модальная область, меню или focused control получает относящийся
   к ней ввод. Поглощённое событие не передаётся содержимому.
4. Команды окна и вкладок проходят общую policy; разрешённые переходы вкладок
   сохраняются согласно действующим dialog rules.
5. Поле получает редактирование и clipboard при focus в поле. Документ
   получает только разрешённые ему команды и жесты.

Открытие перекрывающей области, retirement получателя, отключение мыши
и смена presentation отменяют несовместимый capture. Существующие
`DialogCommandPolicy`, `ModalInputPolicy`, `WindowPointerCapture` и
`ControlCommandRouter` остаются исходной базой этих правил.

## Общий текстовый механизм и политики адаптеров

Geometry port преобразует позиции содержимого в визуальные строки/колонки
и обратно. Source использует логические позиции исходника; Preview —
семантические offsets и отдельную visual affinity для двумерного содержимого.
Контроллер рассчитывает movement, selection, pan, page и reveal по этим
контрактам. Source применяет логическое изменение через session API.

Общий алгоритм сохраняет уже действующие политики: ручной pan Source
сохраняет логический cursor; pan Preview переносит visual caret вместе
с viewport, сохраняя semantic copy selection. Несколько строк формулы могут
соответствовать одному semantic offset. Page Source при выделении сохраняет
существующее правило панорамирования. Эти различия выражаются параметрами
и mappings адаптера, а не отдельными реализациями scroll/navigation.

Набор подготовленных строк и геометрия используются paint, hit testing
и interaction одного состояния. Обычные scroll/selection не повторяют parse,
layout или highlighting стабильного содержимого.

## Матрица обязательных сценариев

| Сценарий | Source | Live Preview | Frozen Preview |
|---|---|---|---|
| Общая команда через меню/клавиатуру | Один dispatch и общая capability policy | То же | То же |
| Редактирование содержимого | Session command при разрешённой mutation | Отказ без raw replay | Отказ без raw replay |
| Find и Save As | Общие editable fields | Те же controls поверх readonly content | Те же controls поверх readonly content |
| Scroll, drag, page, resize | Общий controller, Source geometry/policies | Общий controller, Preview geometry/policies | То же |
| Save | Актуальный buffer | Актуальный live buffer | Raw frozen snapshot |
| Ctrl+P | Создать/активировать Preview справа | Активировать Source слева | Disk reopen либо существующий владелец ресурса |
| Закрытие Source | Save/Discard/Cancel и retirement | Freeze последнего принятого исходника | Editing owner отсутствует |
| Поздний async result | Проверить адресата и версии | Дополнительно generation принятой связи | Результат старой live-связи отвергается |

В подтверждении используются `Y/Ctrl+S=Save`, `N/Ctrl+Q=Discard`, `Esc=Cancel`.
При отсутствии принятой раскладки frozen snapshot берётся из исходника,
захваченного при создании Preview. Чужой dirty buffer при reopen сохраняется.
Недоступный файл и version conflict продолжают обрабатываться existing
storage protocol; автоматический Save в Save As не добавляется.

## Переносимая граница

`CommandInvocation`, document commands/results и registry уже нейтральны.
Каталог `TuinkCommand`, physical input, terminal geometry и widget focus
остаются терминальными там, где выражают действия конкретного frontend.
Переносимые сценарии приложения принимают смысловые решения и opaque
получателей, возвращая запросы действий для платформенного адаптера.

Android и desktop переиспользуют document/session/Markdown contracts
и общие use cases. Их measurement, drawing, IME, touch, clipboard, storage
и lifecycle подключаются собственными адаптерами. Нейтральный engine
не хранит терминальные клетки или пиксельную раскладку.

## Исполняемый маршрут окна

`handleInputPoll` вызывает `routeWindowInput`, затем `dispatchWindowInput`.
Нормальный ввод, захваченный указатель и разрешённый ввод под диалогом
попадают в единственный `dispatchContentInput`. `routeBlockingInput`
возвращает typed решение и не выполняет содержимое. Pointer placements
используют тот же content entry при первоначальном событии и capture.

Панель вкладок маршрутизируется один раз с window/local modal policy.
Confirmation widget выдаёт зарегистрированные команды Save/Discard/Cancel;
решение подтверждения не передаётся дополнительным nullable side channel
рядом с исходным событием или командой.

## Фокус и редактирование полей

`FieldInputDispatcher` выполняет clipboard, перевод approved commands
в TextField actions и admission текстового ввода у Source Find/Replace,
Save As, Open-location и Preview Find. Лимит Preview query — 4096 UTF-16
units; Source fields сохраняют существующее ограничение памятью виджета.
Disabled/unfocused field не получает clipboard или text mutation.

Видимость запроса и получатель клавиатуры разделены. Source и Preview
используют существующие `TabUiState.searchEditorFocused` и search focus manager.
Клик содержимого, клик поля и Tab/ShiftTab меняют фактического владельца;
запрос и matches сохраняются. Cursor и caret overlays получают immutable
проекцию того же focus: поле, содержимое либо скрытый caret под модальным
control. Query scope участвует в общем pointer capture.

Геометрическая eligibility поля и временная недоступность capture под
меню/диалогом разделены. Меню использует логический recipient исходного
поля, сохраняя его selection. Modal transition отменяет capture до смены
владельца. Старый общий `InputActionDispatcher.dispatch` удалён; Source
document dispatcher имеет только необходимый document context.

## Контракты текстовой поверхности

`render.surface.TextSurfaceGeometry<P>` описывает визуальную геометрию,
преобразование позиции и атомарные spans. `TextSurfaceContent<P>` задаёт
смысловые границы позиции. Identity/revision и copy остаются у существующих
session/presentation ports: горячий interaction controller не требует
полного String или нового универсального host interface.

`TextSurfaceInteraction` вычисляет navigation, target, hit, pan, page, clamp
и reveal; его состояние передаётся immutable значениями. `TextSurfacePointer`
владеет gesture state и возвращает selection/scroll/capture intents. Geometry
и placements заимствуются только на время вызова. Controller не удерживает
engine, Markdown-модель или application callbacks; ScrollInteraction/Scrollbar
и Unicode primitives переиспользуются из TUIframe.

Source и Preview сохраняют типы позиций и политики word selection, двумерной
visual affinity и page overlap. Политики представлены данными; алгоритмы
общие.

## Адаптер проекции Markdown

`MarkdownTextSurface` предоставляет geometry и semantic boundary port поверх
принятого layout и существующего bounded MarkdownGeometry. Формулы/Mermaid
сохраняют many-rows-to-one-atom mapping и raw copy semantics. Controller
использует ту же navigation/pointer state machine, что Source.

PreviewSelection хранит semantic selection и visual affinity и применяет
результаты общего controller; старые move/pan/hit вычисления удалены.
MarkdownPreviewState применяет общие clamp/reveal/reflow/pointer результаты.
Query flag назван `searchVisible`: он описывает наличие строки, а focus
по-прежнему принадлежит существующему TabUiState/focus manager.

## Общие текст окна и параметры подсветки

Source rasterizer и Preview renderer вызывают `WindowTextPainter` для
заголовка, status, многострочного prompt и notification. Unicode clipping,
widget tab stops, палитра и размещение этих строк имеют одну реализацию.
Source/Preview frame constructors используют общую проверку prompt ownership.

`PresentationSyntaxPolicy` вычисляет Source availability/master/code-provider
options и независимый Preview color capability. Builder, language factory,
menu capabilities и Preview painter используют готовые решения. Master
Source syntax не пересоздаёт language provider. Policy сохраняет принятую
матрицу предпочтений и существующий settings writer. Клик encoding/EOL
в status вызывает тот же semantic command entry, что клавиатура.

## Переносимый сценарий закрытия

`editor-session.application.ApplicationCloseWorkflow` владеет решениями
close/cancel/confirm/save/wait/timeout. Получатель и операция представлены
opaque типами; завершение сверяется по identity операции. Frontend передаёт
монотонное время и смысловой выбор Save/Discard/Cancel, выполняет effects
и отображает прежние widgets. В workflow нет terminal events, окна, worker
или platform storage.

Открытие, сохранение и lifetime остаются у существующих workspace и
DocumentIoCoordinator; новый сценарий координирует их без второго реестра.
Тот же workflow используется терминальным контроллером, headless session/storage
тестом и внешним Java 17 API-потребителем. Старый TUI WindowCloseWorkflow удалён.

## Lifetime и горячий путь

Interaction controllers не удерживают geometry/layout: pointer сохраняет
только gesture state и семантическую позицию последнего клика. Source positions
не содержат ссылок на engine. Принятый Preview владеет bounded geometry; при
suspend/close/reflow освобождает selection index, geometry, task consumer
и соответствующие leases. Активные frame handles удерживают свою фактическую
плату до закрытия, независимо от eviction.

Parse cache ключуется document identity/revision, работа layout — тем же
ключом и шириной. Geometry получает отдельную producer identity, revision
и bounded row caches; raster keys включают параметры текста и style.
Cache admission refusal использует streaming geometry с теми же смысловыми
границами, не запускает повторную неудачную подготовку каждый кадр.

Навигация и pan используют принятый result; parse/layout/code highlighting
подготавливаются worker только при изменении зависимостей. Narrow source
vertical pan не строит индекс ширин невидимых строк. Владение ресурсами и
устаревшими завершениями проверяется существующими lifecycle/paired/async
тестами; dependency tests закрепляют общие production consumers.

## Единая опубликованная геометрия диалогов

`WindowChrome` готовит immutable `FrameComposition.Layers.blockingBounds`
из фактического picker/help/format placement или clipped inline prompt.
Source и Preview публикуют эти же Layers через `adoptChrome`; input, cursor
и attention читают готовую область. Отдельного `modalInputBounds` и пересчёта
геометрии в TerminalEditorApp больше нет. Frame constructors проверяют,
что bounds соответствуют placements того же кадра; форматный диалог входит
в тот же контракт.

`DocumentFormatPolicy` определяет разрешение по назначению операции, а не
только типу представления. OPEN доступен из Source/live/frozen и reading mode;
FORMAT требует редактируемый Source, REOPEN — Source. Button и semantic APPLY
используют одно решение; текущие busy/operation guards сохраняются.

## Поле и поисковая проекция Preview

Готовый Preview placement всегда сохраняет настоящий TextField.Placement
видимого запроса. Неизменяемый Focus QUERY/CONTENT/NONE — производное
от текущего владельца, а не отдельное состояние фокуса. Его задаёт оконная
подготовка; paint и cursor не выводят focus из наличия поля или caret.
Старые неоднозначные constructors и plain-string fallback удалены.

Смысл Find Next разрешается после проверки исходной команды и до capability
gate: активные Enter/Ctrl+F не превращаются в запрещённое readonly editing.
Пустой запрос с body focus возвращает Ctrl+F в поле.

Preview использует существующий portable SearchMatcher над логическими
строками semantic projection. Общие SearchOptions наследуются при создании
пары и далее принадлежат TabUiState данного представления; allTabs остаётся
Source-only. Новый capped findAll останавливает сканирование без накопления
остальных результатов; одна операция делит nonlinear wildcard work budget
между всеми строками. Диапазоны переводятся в глобальные UTF-16 offsets.
Отказ поиска отделён от layout.error и не удерживает engine/model;
стабильные кадры не повторяют отклонённую работу.
