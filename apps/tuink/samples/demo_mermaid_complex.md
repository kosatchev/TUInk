# Complex Mermaid diagrams

Open this document in TUInk and press `Ctrl+P`.
These small diagrams cover different directions, crossings,
labels and messages. Wide diagrams support horizontal scrolling.
The `╪` symbol marks a crossing of independent edges: there is no turn or
junction at that point. The arrowhead at the border identifies each edge's target.

Examples 1–18 use the supported Mermaid subset and should render
as diagrams. Examples 19–20 deliberately use unsupported constructs:
a `[Mermaid: …]` explanation and the source text are expected for them.

## 1. Left to right: LR

Arrows should lead from input to result; labels read from left to right.

```mermaid
flowchart LR
    A["Input"] --> B("Check") --> C["Result"]
```

## 2. Right to left: RL

Arrows reverse direction while label characters retain their normal order.

```mermaid
flowchart RL
    A["Source"] --> B("Processing") --> C["Target"]
```

## 3. Top to bottom: TD

Two branches diverge after the check and merge before completion.

```mermaid
flowchart TD
    A["Start"] --> B{"Any changes?"}
    B -->|"Yes"| C["Save"]
    B -->|"No"| D["Continue"]
    C --> E["Finish"]
    D --> E
```

## 4. Bottom to top: BT

The starting point is at the bottom. Arrows enter subsequent nodes from below.

```mermaid
flowchart BT
    A["Request"] --> B("Calculation") --> C["Response"]
```

## 5. Decision diamond and branch merge

The decision should be diamond-shaped. Both branch labels stay visible,
and the shared final node appears only once.

```mermaid
flowchart LR
    Open["Open document"] --> Mode{"Which mode?"}
    Mode -->|"Source"| Edit["Editor"]
    Mode -->|"Preview"| Preview["Renderer"]
    Edit --> Save["Save"]
    Preview --> Save
```

## 6. Two crossing edges

Edge A leads only to D; edge B leads only to C.
The crossing must not become a junction.

```mermaid
flowchart LR
    A["A"]
    B["B"]
    C["C"]
    D["D"]
    A --> D
    B --> C
```

## 7. Two labeled edges between the same nodes

The choice must retain both edges and both labels: Yes and No.
Neither edge should hide the other.

```mermaid
flowchart LR
    A{"Choice"}
    B["Next step"]
    A -->|"Yes"| B
    A -->|"No"| B
```

## 8. Multiple lines in a horizontal diagram

Line breaks in nodes and edge labels are preserved. Arrows do not pass
through text and enter node borders.

```mermaid
flowchart LR
    A["Load<br/>document"] -->|"Check<br/>format"| B{"File<br/>available?"}
    B -->|"Yes"| C("Read<br/>contents")
    B -->|"No"| D["Show<br/>message"]
```

## 9. Multiple lines in a vertical diagram

Edge labels, including two-line labels, should fit between nodes.

```mermaid
flowchart TD
    A["Select<br/>repository"] -->|"Open<br/>working tree"| B{"Any<br/>changes?"}
    B -->|"Changes<br/>found"| C["Show diff"]
    B -->|"Working tree<br/>clean"| D["Show status"]
```

## 10. Edges across intermediate levels

Two long edges appear beside the main chain. Their labels and directions
should remain intact.

```mermaid
flowchart LR
    A["Input"] --> B["Parsing"] --> C["Check"] --> D["Output"]
    A -->|"Fast path"| D
    B -.->|"Recheck"| D
```

## 11. Solid and dashed edges

Required steps use solid edges; the optional check uses
dashed edges. Both paths remain distinct at the branch.

```mermaid
flowchart LR
    A["Change"] --> B["Build"] --> C["Delivery"]
    A -.->|"Optional"| D("Style check")
    D -.-> C
```

## 12. Large document-processing diagram

Three sources merge into processing, followed by separate paths for errors,
editing and saving. Check the long edge from the file version to the
write check and the transition from Preview to Source: neither should form
junctions with intermediate nodes.

```mermaid
flowchart TD
    A["File"] --> Merge("Processing")
    B["Buffer"] --> Merge
    C["Snapshot"] --> Merge
    A -.-> Base["Disk version"]
    Merge --> Decode["Detect encoding"]
    Decode --> Parse{"Parse successful?"}
    Parse -->|"Yes"| Projection["Prepare text"]
    Parse -->|"No"| Error["Diagnostics"]
    Projection --> D["Source"]
    Projection --> E["Preview"]
    E -.->|"Ctrl+P"| D
    D --> Save["Write file"]
    Error --> Notice["Show message"]
    Save --> Receipt{"Write accepted?"}
    Base -.-> Receipt
    Receipt -->|"Yes"| Done["Done"]
    Receipt -->|"No"| Conflict["Check conflict"]
```

## 13. Wide characters, combining marks and tabs

Borders must remain aligned next to CJK characters and emoji.
The final letter in `Café` is an `e` followed by a separate combining accent.
The last label contains a literal Tab between `name` and `value`.

```mermaid
flowchart LR
    A["File / 文档"] --> B{"Café ready?"}
    B -->|"Yes"| C["Done 😀"]
    B -->|"No"| D["name	value"]
```

## 14. Sequence: request and reply

Replies use dashed lines in the reverse direction. Messages read
from top to bottom without labels overlapping lifelines.

```mermaid
sequenceDiagram
    participant U as User
    participant E as Editor
    participant S as Storage
    U->>E: Open document
    E->>S: Read file
    S-->>E: Return contents
    E-->>U: Show document
```

## 15. Actor participant and four lifelines

Participant names and order are preserved; messages can pass
intermediate participants to reach a more distant recipient.

```mermaid
sequenceDiagram
    actor U as User
    participant UI as Interface
    participant E as Editor
    participant FS as Files
    U->>UI: Select file
    UI->>FS: Check availability
    FS-->>UI: File available
    UI->>E: Open document
    E-->>U: Document ready
```

## 16. Headers of different heights

Headers contain one, two or three lines. Each participant's lifeline
should meet its border without gaps above or below.

```mermaid
sequenceDiagram
    participant U as User
    participant E as Document<br/>editor
    participant R as Terminal<br/>Markdown<br/>preview
    U->>E: Edit text
    E->>R: Send new version
    R-->>E: Preview ready
    E-->>U: Show result
```

## 17. Self messages

Two self messages form separate loops without obscuring neighboring messages.

```mermaid
sequenceDiagram
    participant E as Editor
    participant W as Worker
    E->>E: Prepare snapshot
    E->>W: Send document
    W->>W: Parse and validate
    W-->>E: Return result
    E-->>E: Update state
```

## 18. Long labels and multiple recipients

Message line breaks are preserved. Solid requests and dashed replies
remain distinct, including when crossing an intermediate lifeline.

```mermaid
sequenceDiagram
    participant U as Client
    participant E as Editor
    participant S as Saving
    participant F as File
    U->>E: Save the<br/>current version
    E->>F: Check:<br/>name and access
    F-->>E: Check<br/>complete
    E->>S: Write document
    S->>F: Transfer<br/>document bytes
    F-->>S: Write confirmed
    S-->>U: Save<br/>complete
```

## Deliberately unsupported examples

The next two diagrams check clear refusal. A `[Mermaid: …]` explanation
and the source text are expected here. These examples must not look like
successfully rendered diagrams with missing edges.

### 19. Cyclic flowchart

```mermaid
flowchart LR
    A["First step"] --> B["Second step"]
    B --> A
```

### 20. Subgraph group

```mermaid
flowchart LR
    subgraph Group
        A["One node"] --> B["Another node"]
    end
```
