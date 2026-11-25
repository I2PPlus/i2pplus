/**
 * Tier-2 pure tests for the change log layout
 * (apps/routerconsole/jsp/js/changelog.js), kept DOM-free so the rules can be
 * pinned without a browser.
 *
 * history.txt is fixed-width text and the console page drops it into one <pre>,
 * which the theme then lays out in CSS columns - and a multi-column container
 * breaks anywhere. So an entry is cut across a column, a sub-list item is cut
 * mid-sentence, and an 80-column line in a narrower column wraps at the container
 * edge with no hanging indent, which makes an item's continuation read as another
 * item. The script rebuilds the page as a list and cuts only between whole
 * entries, and these pin the rules:
 *
 *   1. parsing recovers the structure the file states - one entry per
 *      `* Subsystem:` line, its continuation prose joined into it, and its
 *      sub-list items with theirs - and drops the marker characters, because the
 *      element says what the marker said;
 *   2. an entry is never cut unless it is taller than a column, and then only
 *      between its sub-list items, which are never cut themselves;
 *   3. the columns are filled: the cut minimises the tallest run, since every
 *      column is as tall as its tallest run, and the runs stay contiguous so the
 *      log reads down one column and then the next.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  columnCount,
  parseBlock,
  partition,
  splitSections
} from "../../../../apps/routerconsole/jsp/js/changelog.js";

/** One dated block: a header, two entries, one of them with a sub-list. */
const SECTION = [
  "2026-11-24 dr|z3d",
  "  * Router: First entry, one line",
  "  * Streaming: Second entry opener",
  "    continuation of the opener",
  "    - first sub-list item, three",
  "      lines long here",
  "      and this is the third",
  "    - second sub-list item, also",
  "      three lines long here",
  "      and this is the third"
].join("\n");

/** The page's own slice of the real change log: the first 768 lines. */
const REAL = readFileSync(new URL("../../../../docs/history.txt", import.meta.url), "utf8")
    .split("\n").slice(0, 768).join("\n");

test("dated blocks separate at the blank lines history.txt puts between them", () => {
  const sections = splitSections("2026-01-01 a\n  * A: one\n\n\n2026-01-02 b\n  * B: two\n");
  assert.equal(sections.length, 2);
  assert.match(sections[0], /^2026-01-01/);
  assert.match(sections[1], /^2026-01-02/);
  assert.deepEqual(splitSections("   \n\n  \n"), []);
});

test("an entry keeps its prose and its whole sub-list", () => {
  const block = parseBlock(SECTION);
  assert.equal(block.date, "2026-11-24 dr|z3d");
  assert.equal(block.entries.length, 2);
  assert.deepEqual(block.entries[0], {head: "Router: First entry, one line", items: []});
  const entry = block.entries[1];
  // The marker characters are gone, and a continuation line is joined to what it
  // continues rather than standing as its own thing.
  assert.equal(entry.head, "Streaming: Second entry opener continuation of the opener");
  assert.equal(entry.items.length, 2);
  assert.equal(entry.items[0],
      "first sub-list item, three lines long here and this is the third");
  assert.equal(entry.items[1],
      "second sub-list item, also three lines long here and this is the third");
});

test("a block with no dated header still yields its entries", () => {
  const block = parseBlock("  * Router: no header here\n    and its continuation");
  assert.equal(block.date, null);
  assert.equal(block.entries.length, 1);
  assert.equal(block.entries[0].head, "Router: no header here and its continuation");
});

test("text ahead of any entry, or an item ahead of any entry, is not lost", () => {
  // history.txt's older blocks open with prose that is not a `* Subsystem:` line.
  const block = parseBlock("2004-xx-xx jrandom\n  * Something else entirely");
  assert.equal(block.date, null);
  assert.equal(block.entries[0].head, "2004-xx-xx jrandom");
  assert.equal(block.entries[1].head, "Something else entirely");
  const orphan = parseBlock("    - an item with no entry above it");
  assert.equal(orphan.entries.length, 1);
  assert.deepEqual(orphan.entries[0], {head: "", items: ["an item with no entry above it"]});
});

test("blank lines inside a block change nothing", () => {
  assert.deepEqual(parseBlock(SECTION.replace("\n  * Router", "\n\n  * Router")),
      parseBlock(SECTION));
});

test("parsing loses no words and invents none", () => {
  const words = (t) => t.split(/\s+/).filter((w) => w.length > 0);
  const blocks = splitSections(REAL).map(parseBlock);
  const back = blocks.map(function(b) {
    return [b.date, ...b.entries.map((e) => [e.head, ...e.items].join(" "))].join(" ");
  }).join(" ");
  // The marker characters are the only words the parse may drop.
  const before = words(REAL).filter((w) => w !== "*" && w !== "-");
  assert.deepEqual(words(back).filter((w) => w !== "*" && w !== "-"), before);
});

test("the real change log parses into the entries and items it holds", () => {
  const lines = REAL.split("\n");
  const entries = lines.filter((l) => /^ {2}\* /.test(l)).length;
  const items = lines.filter((l) => /^ {4}- /.test(l)).length;
  const blocks = splitSections(REAL).map(parseBlock);
  const parsed = blocks.reduce(function(sum, b) {
    return sum + b.entries.reduce((n, e) => n + Math.max(e.items.length, 1), 0);
  }, 0);
  assert.equal(blocks.reduce((n, b) => n + b.entries.length, 0), entries);
  assert.equal(blocks.reduce((n, b) => n + b.entries.reduce((m, e) => m + e.items.length, 0), 0), items);
  assert.ok(parsed > entries, "an entry with items counts once per item once split");
  for (const block of blocks) {
    for (const entry of block.entries) {
      assert.ok(entry.head.length > 0 || entry.items.length > 0, "an empty entry");
      for (const item of entry.items) {assert.ok(item.length > 0, "an empty item");}
    }
  }
});

test("the column count is the fewest that keeps every column inside the box", () => {
  // 1,000px of log will not stand in one 600px column, so it takes two.
  assert.equal(columnCount([400, 300, 300], 600, 1), 2);
  // It would fit in one, but the theme asks for two, and a gap where the theme
  // drew a column is worse than a short column.
  assert.equal(columnCount([400, 300], 600, 2), 2);
  // An entry taller than the box cannot be cut, so it stands in a column of its
  // own and the box is measured against that rather than against itself.
  assert.equal(columnCount([900, 300, 300], 600, 1), 2);
  assert.equal(columnCount([900, 900, 300], 600, 1), 3);
  // The order is fixed, so a run cannot reach past an entry that fills the box to
  // borrow room from the next one.
  assert.equal(columnCount([500, 500, 500], 600, 1), 3);
  // An unmeasurable box, or nothing to lay out, leaves the theme's count alone.
  assert.equal(columnCount([100, 100], 0, 3), 3);
  assert.equal(columnCount([], 600, 2), 2);
});

test("the cut keeps the runs contiguous and in order", () => {
  const runs = partition([3, 1, 4, 1, 5, 9, 2, 6], 3);
  assert.equal(runs.length, 3);
  assert.deepEqual(runs.flat(), [0, 1, 2, 3, 4, 5, 6, 7]);
  for (const run of runs) {
    assert.ok(run.length > 0, "no column is empty");
    assert.deepEqual(run, [...run].sort((a, b) => a - b), "a run is contiguous");
  }
});

test("the cut minimises the tallest run, which is the dead space", () => {
  const heights = [7, 1, 1, 7, 1, 1, 7];
  const runs = partition(heights, 3);
  const sum = (rs) => rs.reduce((s, i) => s + heights[i], 0);
  const tallest = (rs) => Math.max(...rs.map(sum));
  // 25px of log in three runs cannot do better than 9.
  assert.equal(tallest(runs), 9);
  // Brute force every other contiguous 3-run split of the same sequence: none is
  // tighter.
  const tallestValues = (vs) => Math.max(...vs.map((v) => v.reduce((s, x) => s + x, 0)));
  let best = Infinity;
  for (let a = 1; a < heights.length - 1; a++) {
    for (let b = a + 1; b < heights.length; b++) {
      best = Math.min(best, tallestValues([
        heights.slice(0, a), heights.slice(a, b), heights.slice(b)
      ]));
    }
  }
  assert.equal(tallest(runs), best);
});

test("the real log balances: no column is left short by more than one unit", () => {
  // Heights stand in for the rendered units: the same numbers the layout feeds
  // the cut, one per entry, in document order.
  const heights = splitSections(REAL).map(parseBlock)
      .flatMap((b) => b.entries.map((e) => 40 + 18 * (e.items.length + 1)));
  const total = heights.reduce((a, b) => a + b, 0);
  for (const columns of [1, 2, 3, 4]) {
    const runs = partition(heights, columns);
    assert.equal(runs.length, Math.min(columns, heights.length));
    assert.equal(runs.flat().length, heights.length, "no unit lost");
    const tall = runs.map(sumOf);
    const ideal = total / runs.length;
    assert.ok(Math.max(...tall) - Math.min(...tall) <= Math.max(...heights),
        `columns differ by more than one unit: ${tall}`);
    assert.ok(Math.max(...tall) <= ideal + Math.max(...heights),
        `tallest column is worse than the ideal by more than one unit: ${tall} vs ${ideal}`);
  }
  // And at the count the box asks for, every column fits the box: the cut is what
  // makes that true, since no unit here is taller than the box itself.
  const box = 580;
  const runs = partition(heights, columnCount(heights, box, 2));
  for (const run of runs) {
    assert.ok(sumOf(run) <= box, `a column of ${sumOf(run)} overflows a box of ${box}`);
  }
  // A unit taller than the box is the only thing that can put a column over it,
  // and the count is then measured against that unit instead.
  const tall = [...heights];
  tall[3] = box + 400;
  const over = partition(tall, columnCount(tall, box, 2));
  for (const run of over) {
    const height = run.reduce((s, i) => s + tall[i], 0);
    assert.ok(height <= box + 400, `a column of ${height} overflows the tallest entry`);
  }
  function sumOf(run) {return run.reduce((s, i) => s + heights[i], 0);}
});

test("a limited cut keeps every run inside the limit it is given", () => {
  // The count and the cut are asked for together: the count is the fewest runs
  // that can hold the sequence inside the box, and the cut is then the one that
  // overflows it least, so no column is left taller than it has to be.
  const heights = [700, 100, 100, 100, 100, 700, 300, 300, 300, 100];
  const limit = 600;
  const runs = partition(heights, columnCount(heights, limit, 1), limit);
  assert.ok(runs.length > 1, "the sequence needs more than one run");
  for (const run of runs) {
    const height = run.reduce((sum, i) => sum + heights[i], 0);
    // An entry taller than the box cannot be cut, so it is the one thing a run
    // may be measured against.
    assert.ok(height <= Math.max(limit, Math.max(...heights)),
        `a run of ${height} overflows the box`);
  }
  // Without the limit the same cut balances only the tallest run, and is free to
  // leave another run overflowing the box: its tallest run is never taller than
  // the limited cut's, because that one trades balance for fitting the box.
  const free = partition(heights, runs.length);
  const tallest = (rs) => Math.max(...rs.map((r) => r.reduce((s, i) => s + heights[i], 0)));
  assert.ok(tallest(free) <= tallest(runs),
      `the balanced cut is taller: ${tallest(free)} over ${tallest(runs)}`);
  const overflow = (rs) => rs.reduce((s, r) => s + Math.max(0, r.reduce((x, i) => x + heights[i], 0) - limit), 0);
  assert.ok(overflow(runs) <= overflow(free),
      `the limited cut overflows more: ${overflow(runs)} over ${overflow(free)}`);
});

test("asking for more columns than there are units gives one unit each", () => {
  assert.deepEqual(partition([5, 6], 4), [[0], [1]]);
  assert.deepEqual(partition([], 3), [[]]);
  assert.deepEqual(partition([5, 6, 7], 1), [[0, 1, 2]]);
});
