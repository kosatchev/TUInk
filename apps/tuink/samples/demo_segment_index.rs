// Demo: Segment index for append-only logs with overlap checks and lookups.
use std::collections::{BTreeMap, HashMap};

#[derive(Debug, Clone)]
struct Segment {
    id: u64,
    topic: String,
    from_offset: u64,
    to_offset: u64,
    file: String,
    checksum: u64,
}

#[derive(Debug, Default)]
struct SegmentIndex {
    by_topic: HashMap<String, BTreeMap<u64, Segment>>,
    by_id: HashMap<u64, (String, u64)>,
}

impl SegmentIndex {
    fn insert(&mut self, segment: Segment) -> Result<(), String> {
        if segment.from_offset >= segment.to_offset {
            return Err("bad range: from_offset >= to_offset".to_string());
        }

        let topic = segment.topic.clone();
        let start = segment.from_offset;
        let id = segment.id;

        let entries = self.by_topic.entry(topic.clone()).or_default();
        if let Some((_, prev)) = entries.range(..=start).next_back() {
            if prev.to_offset > start {
                return Err(format!(
                    "overlap with segment {} [{}, {})",
                    prev.id, prev.from_offset, prev.to_offset
                ));
            }
        }

        if let Some((_, next)) = entries.range(start..).next() {
            if next.from_offset < segment.to_offset {
                return Err(format!(
                    "overlap with segment {} [{}, {})",
                    next.id, next.from_offset, next.to_offset
                ));
            }
        }

        entries.insert(start, segment);
        self.by_id.insert(id, (topic, start));
        Ok(())
    }

    fn remove(&mut self, id: u64) -> Option<Segment> {
        let (topic, start) = self.by_id.remove(&id)?;
        let tree = self.by_topic.get_mut(&topic)?;
        let removed = tree.remove(&start);
        if tree.is_empty() {
            self.by_topic.remove(&topic);
        }
        removed
    }

    fn find(&self, topic: &str, offset: u64) -> Option<&Segment> {
        let tree = self.by_topic.get(topic)?;
        let (_, seg) = tree.range(..=offset).next_back()?;
        if offset < seg.to_offset {
            Some(seg)
        } else {
            None
        }
    }

    fn verify_checksums(&self) -> Vec<String> {
        let mut errors = Vec::new();
        for (topic, tree) in &self.by_topic {
            for seg in tree.values() {
                let expected = fake_checksum(seg.id, seg.from_offset, seg.to_offset);
                if expected != seg.checksum {
                    errors.push(format!(
                        "topic={} id={} checksum mismatch: expected {}, got {}",
                        topic, seg.id, expected, seg.checksum
                    ));
                }
            }
        }
        errors
    }

    fn count(&self) -> usize {
        self.by_id.len()
    }
}

fn fake_checksum(id: u64, from: u64, to: u64) -> u64 {
    let mut x = 0xcbf29ce484222325u64;
    for part in [id, from, to] {
        x ^= part;
        x = x.wrapping_mul(0x100000001b3);
    }
    x
}

fn make_segment(id: u64, topic: &str, from: u64, to: u64, file: &str) -> Segment {
    Segment {
        id,
        topic: topic.to_string(),
        from_offset: from,
        to_offset: to,
        file: file.to_string(),
        checksum: fake_checksum(id, from, to),
    }
}

fn demo() -> Result<(), String> {
    let mut index = SegmentIndex::default();
    index.insert(make_segment(1, "orders", 0, 1000, "orders-000.log"))?;
    index.insert(make_segment(2, "orders", 1000, 2000, "orders-001.log"))?;
    index.insert(make_segment(3, "billing", 0, 500, "billing-000.log"))?;

    if let Some(seg) = index.find("orders", 1300) {
        println!("segment for orders@1300 => id={}, file={}", seg.id, seg.file);
    }

    let checksum_errors = index.verify_checksums();
    if !checksum_errors.is_empty() {
        return Err(format!("checksum errors: {}", checksum_errors.join("; ")));
    }

    index.remove(2);
    println!("index size after remove: {}", index.count());
    Ok(())
}

fn main() {
    if let Err(err) = demo() {
        eprintln!("error: {err}");
        std::process::exit(1);
    }
}
