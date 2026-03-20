#!/usr/bin/env node
// Demo: Parses structured logs and prints level/module statistics.
"use strict";

const fs = require("fs");
const path = require("path");

function parseLine(line) {
  const pattern = /^\[(?<time>[^\]]+)\]\s+(?<level>[A-Z]+)\s+(?<module>[a-z.]+)\s+-\s+(?<message>.+)$/;
  const match = line.match(pattern);
  if (!match) return null;
  return {
    time: match.groups.time,
    level: match.groups.level,
    module: match.groups.module,
    message: match.groups.message,
  };
}

function increment(map, key) {
  const value = map.get(key) || 0;
  map.set(key, value + 1);
}

function analyze(lines) {
  const byLevel = new Map();
  const byModule = new Map();
  const timeline = [];

  for (const line of lines) {
    const event = parseLine(line);
    if (!event) continue;
    increment(byLevel, event.level);
    increment(byModule, event.module);
    timeline.push(event);
  }

  timeline.sort((a, b) => a.time.localeCompare(b.time));
  return { byLevel, byModule, timeline };
}

function printMap(title, map) {
  console.log(`\n${title}`);
  console.log("-".repeat(title.length));
  const rows = [...map.entries()].sort((a, b) => b[1] - a[1]);
  for (const [key, value] of rows) {
    console.log(`${key.padEnd(16)} ${String(value).padStart(4)}`);
  }
  if (rows.length === 0) {
    console.log("(empty)");
  }
}

function printTimeline(events, limit) {
  console.log("\nRecent events");
  console.log("-------------");
  const tail = events.slice(-limit);
  for (const event of tail) {
    const shortMsg =
      event.message.length > 50 ? event.message.slice(0, 47) + "..." : event.message;
    console.log(`${event.time} ${event.level.padEnd(5)} ${event.module.padEnd(14)} ${shortMsg}`);
  }
  if (tail.length === 0) {
    console.log("(no matching events)");
  }
}

function readFileLines(filePath) {
  const fullPath = path.resolve(filePath);
  const content = fs.readFileSync(fullPath, "utf8");
  return content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

function main(argv) {
  if (argv.length < 3) {
    console.error("Usage: node demo_log_report.js <log-file> [limit]");
    return 1;
  }

  const filePath = argv[2];
  const limit = Number(argv[3] || "10");
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) {
    console.error("Limit must be an integer from 1 to 200.");
    return 2;
  }

  const lines = readFileLines(filePath);
  const report = analyze(lines);

  printMap("Events by level", report.byLevel);
  printMap("Events by module", report.byModule);
  printTimeline(report.timeline, limit);
  console.log(`\nTotal parsed events: ${report.timeline.length}`);
  return 0;
}

if (require.main === module) {
  process.exit(main(process.argv));
}
