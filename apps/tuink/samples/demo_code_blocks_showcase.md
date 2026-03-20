<!-- Demo: Long Markdown with large fenced code blocks for highlighting and scrolling checks. -->
# Sample Code Blocks Showcase

Этот файл нужен как длинный markdown-текст с большими вставками кода для проверки прокрутки, подсветки fenced-блоков и читаемости на длинных страницах.

## Java Block (Task Aggregator)

```java
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskAggregator {
    private final Map<String, Integer> counters = new HashMap<>();

    public void addEvent(String type) {
        counters.put(type, counters.getOrDefault(type, 0) + 1);
    }

    public List<String> renderTop(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counters.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<String> rows = new ArrayList<>();
        rows.add("report_at=" + Instant.now());
        rows.add("total_types=" + entries.size());

        int n = Math.min(limit, entries.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            rows.add(String.format("%02d. %-16s %d", i + 1, e.getKey(), e.getValue()));
        }
        return rows;
    }

    public static void main(String[] args) {
        TaskAggregator aggr = new TaskAggregator();
        String[] events = {
            "job.created", "job.started", "job.started", "job.finished",
            "job.created", "job.failed", "job.created", "job.finished"
        };
        for (String event : events) {
            aggr.addEvent(event);
        }

        for (String row : aggr.renderTop(5)) {
            System.out.println(row);
        }
    }
}
```

## Bash Block (Backup Rotation)

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${1:-./backups}"
KEEP_DAYS="${2:-7}"
TS="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="${BACKUP_DIR}/snapshot-${TS}.tar.gz"

mkdir -p "$BACKUP_DIR"

echo "[1/5] collecting sources"
SOURCES=(
  "./README.md"
  "./build.gradle"
  "./research"
)

TMP_LIST="$(mktemp)"
for path in "${SOURCES[@]}"; do
  if [[ -e "$path" ]]; then
    echo "$path" >> "$TMP_LIST"
  fi
done

echo "[2/5] creating archive: $ARCHIVE"
tar -czf "$ARCHIVE" -T "$TMP_LIST"
rm -f "$TMP_LIST"

echo "[3/5] writing checksum"
sha256sum "$ARCHIVE" > "${ARCHIVE}.sha256"

echo "[4/5] pruning old archives"
find "$BACKUP_DIR" -type f -name 'snapshot-*.tar.gz' -mtime "+${KEEP_DAYS}" -print -delete
find "$BACKUP_DIR" -type f -name 'snapshot-*.tar.gz.sha256' -mtime "+${KEEP_DAYS}" -print -delete

echo "[5/5] index file"
{
  echo "# backup index"
  echo "generated_at=$(date -Iseconds)"
  ls -1 "$BACKUP_DIR"/snapshot-*.tar.gz 2>/dev/null || true
} > "${BACKUP_DIR}/LATEST_INDEX.txt"

echo "[6/6] done"
ls -lh "$BACKUP_DIR" | sed -n '1,20p'
```

## Rust Block (Sliding Window)

```rust
use std::collections::VecDeque;

#[derive(Debug)]
struct SlidingWindow {
    values: VecDeque<i64>,
    cap: usize,
    sum: i64,
}

impl SlidingWindow {
    fn new(cap: usize) -> Self {
        Self {
            values: VecDeque::with_capacity(cap),
            cap,
            sum: 0,
        }
    }

    fn push(&mut self, v: i64) {
        self.values.push_back(v);
        self.sum += v;
        if self.values.len() > self.cap {
            if let Some(x) = self.values.pop_front() {
                self.sum -= x;
            }
        }
    }

    fn avg(&self) -> f64 {
        if self.values.is_empty() {
            0.0
        } else {
            self.sum as f64 / self.values.len() as f64
        }
    }

    fn max(&self) -> Option<i64> {
        self.values.iter().copied().max()
    }
}

fn main() {
    let mut w = SlidingWindow::new(5);
    for n in [10, 11, 20, 9, 8, 14, 30, 28] {
        w.push(n);
        println!("n={n:>2} avg={:.2} max={:?}", w.avg(), w.max());
    }
}
```

## SQL Block (Analytics Query)

```sql
WITH raw_events AS (
    SELECT * FROM (VALUES
        (1, 'orders',  'created',  TIMESTAMP '2026-03-01 09:00:00'),
        (2, 'orders',  'paid',     TIMESTAMP '2026-03-01 09:05:00'),
        (3, 'orders',  'shipped',  TIMESTAMP '2026-03-01 10:00:00'),
        (4, 'billing', 'invoice',  TIMESTAMP '2026-03-01 10:10:00'),
        (5, 'orders',  'created',  TIMESTAMP '2026-03-01 11:12:00'),
        (6, 'orders',  'paid',     TIMESTAMP '2026-03-01 11:17:00'),
        (7, 'billing', 'refund',   TIMESTAMP '2026-03-01 11:45:00'),
        (8, 'orders',  'failed',   TIMESTAMP '2026-03-01 12:01:00')
    ) AS t(id, domain, event_type, event_time)
),
agg AS (
    SELECT
        domain,
        event_type,
        COUNT(*) AS cnt,
        MIN(event_time) AS first_seen,
        MAX(event_time) AS last_seen
    FROM raw_events
    GROUP BY domain, event_type
),
ranked AS (
    SELECT
        domain,
        event_type,
        cnt,
        first_seen,
        last_seen,
        ROW_NUMBER() OVER (PARTITION BY domain ORDER BY cnt DESC, event_type) AS rn
    FROM agg
)
SELECT
    domain,
    event_type,
    cnt,
    first_seen,
    last_seen
FROM ranked
WHERE rn <= 3
ORDER BY domain, cnt DESC, event_type;
```
