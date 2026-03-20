#!/usr/bin/env python3
# Demo: Small task CLI with JSON storage and basic CRUD commands.
import argparse
import json
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List


@dataclass
class Task:
    id: int
    title: str
    priority: str
    done: bool
    tags: List[str]
    created_at: str
    done_at: str | None = None


class TaskStore:
    def __init__(self, path: Path):
        self.path = path
        self.items: Dict[int, Task] = {}
        self.next_id = 1
        self.load()

    def load(self) -> None:
        if not self.path.exists():
            return
        data = json.loads(self.path.read_text(encoding="utf-8"))
        self.next_id = data.get("next_id", 1)
        for raw in data.get("items", []):
            task = Task(**raw)
            self.items[task.id] = task

    def save(self) -> None:
        payload = {
            "next_id": self.next_id,
            "items": [asdict(task) for task in self.items.values()],
        }
        self.path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def add(self, title: str, priority: str, tags: List[str]) -> Task:
        task = Task(
            id=self.next_id,
            title=title,
            priority=priority,
            done=False,
            tags=tags,
            created_at=datetime.utcnow().isoformat(timespec="seconds") + "Z",
        )
        self.items[task.id] = task
        self.next_id += 1
        self.save()
        return task

    def list(self, only_open: bool = False) -> List[Task]:
        tasks = list(self.items.values())
        tasks.sort(key=lambda t: (t.done, t.priority, t.id))
        if only_open:
            tasks = [t for t in tasks if not t.done]
        return tasks

    def mark_done(self, task_id: int) -> Task:
        task = self.items[task_id]
        task.done = True
        task.done_at = datetime.utcnow().isoformat(timespec="seconds") + "Z"
        self.save()
        return task

    def remove(self, task_id: int) -> None:
        del self.items[task_id]
        self.save()


def format_task(task: Task) -> str:
    state = "x" if task.done else " "
    tags = ", ".join(task.tags) if task.tags else "-"
    return f"[{state}] #{task.id:03d} {task.title} (p:{task.priority}, tags:{tags})"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Small task CLI")
    parser.add_argument("--file", default="tasks.json", help="Path to data file")
    sub = parser.add_subparsers(dest="command", required=True)

    cmd_add = sub.add_parser("add", help="Create a task")
    cmd_add.add_argument("title")
    cmd_add.add_argument("--priority", default="normal", choices=["low", "normal", "high"])
    cmd_add.add_argument("--tags", default="", help="Comma-separated tags")

    cmd_list = sub.add_parser("list", help="List tasks")
    cmd_list.add_argument("--open", action="store_true", help="Only open tasks")

    cmd_done = sub.add_parser("done", help="Mark task as done")
    cmd_done.add_argument("id", type=int)

    cmd_rm = sub.add_parser("rm", help="Remove task")
    cmd_rm.add_argument("id", type=int)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    store = TaskStore(Path(args.file))

    if args.command == "add":
        tags = [t.strip() for t in args.tags.split(",") if t.strip()]
        task = store.add(args.title, args.priority, tags)
        print("Created:", format_task(task))
        return 0

    if args.command == "list":
        tasks = store.list(only_open=args.open)
        if not tasks:
            print("No tasks found.")
            return 0
        for task in tasks:
            print(format_task(task))
        return 0

    if args.command == "done":
        task = store.mark_done(args.id)
        print("Done:", format_task(task))
        return 0

    if args.command == "rm":
        store.remove(args.id)
        print(f"Removed task #{args.id}")
        return 0

    parser.error("Unknown command")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
