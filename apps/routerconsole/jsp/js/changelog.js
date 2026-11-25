/**
 * @module changelog
 * @description Rebuilds the in-console change log as a list and lays it out in
 * the theme's columns without cutting an entry.
 *
 * history.txt is fixed-width text written for an 80-column terminal: a dated
 * header, then one entry per `* Subsystem:` line, that entry's continuations
 * indented four spaces under it, and any sub-list with `- ` four spaces in and
 * the item's own continuations six. The page drops the file into one `<pre>`,
 * which the theme lays out as a multi-column box of fixed height - and a
 * multi-column container breaks anywhere. So an entry is cut across a column, a
 * sub-list item is cut mid-sentence, and an 80-column line in a narrower column
 * wraps at the container edge with no hanging indent, which makes an item's
 * continuation read as another item.
 *
 * This throws that layout away and builds the document the text describes: one
 * element per entry, a nested list for each entry's sub-list, inside the same
 * `<pre>`, so the theme's box, font size, borders and scroll bars are unchanged
 * and the prose is set in the body font instead of the terminal one. The browser
 * then wraps the prose to the width it is given, the hanging indents and the
 * house's star and bullet icons come from CSS rather than from counted spaces,
 * and the only thing left to decide is where to cut. That is decided here:
 *
 *   1. an entry is never cut unless it is taller than the box itself, and then
 *      first between its sub-list items, which are never cut themselves. Only an
 *      entry still too tall after that - a single paragraph longer than the box -
 *      is broken at a word boundary, its pieces marked as continuations;
 *   2. a dated header is never a cut point and never stands alone - it heads the
 *      block's entries in every column they appear in;
 *   3. the columns are the theme's own - the same count, width and gap its
 *      multi-column box would have used, which is two columns at the widths the
 *      theme splits the page and one below them. Two columns cap every column at
 *      the box's height and continue the log into as many more columns as that
 *      needs, so the box scrolls sideways and never up and down; one column runs
 *      down past the box instead, so it scrolls down. Either way the sequence is
 *      cut into contiguous runs of near-equal height: minimising the tallest run
 *      minimises how far the box scrolls, and contiguous runs keep the log
 *      reading down one column and then the next.
 *
 * Heights are measured from the rendered elements, so the balance holds at any
 * font size and in any theme. The parsing, the column count and the cut are
 * exported and DOM-free, so the rules can be pinned without a browser.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/** A dated header line, `2026-11-24 dr|z3d`. */
const DATE_LINE = /^\d{4}-\d{2}-\d{2}\s/;

/** The first line of an entry, `  * Subsystem: ...`. */
const ENTRY_LINE = /^ {2}\* /;

/** The first line of a sub-list item, `    - ...`. */
const ITEM_LINE = /^ {4}- /;

/**
 * Space between two columns, in CSS pixels, used only when the theme gives the
 * box no `column-gap` of its own to read.
 */
const COLUMN_GAP = 60;

/** Reflow no more often than this while the window is being dragged. */
const REFLOW_DEBOUNCE_MS = 250;

/**
 * How many times one reflow may cut, measure and cut again. The count that keeps
 * every column inside the box is found by measuring what the last cut came to and
 * asking for the columns that height needs, so it converges in two or three; the
 * limit is only there so a box that cannot be satisfied - one shorter than the
 * tallest entry - stops trying.
 */
const LAYOUT_PASSES = 6;

/**
 * How far inside the box a column is kept. The cut is planned from the heights of
 * the units and of the dated block's wrapper, and what the browser paints is a
 * pixel or two more than that sum: without the margin, a column planned at exactly
 * the box's height lands a few pixels past it, and a box that is a few pixels too
 * short grows a vertical scrollbar, which is the one thing the sideways layout is
 * there to avoid.
 */
const LAYOUT_SLACK_PX = 12;

/**
 * The dated blocks of the change log, newest first, as history.txt separates
 * them with a blank line.
 *
 * @param {string} text - the change log as the page holds it
 * @returns {string[]} one item per dated block, none empty
 */
export function splitSections(text) {
  return text.trim().split(/\n{2,}/).filter(function(section) {
    return section.trim().length > 0;
  });
}

/**
 * One dated block read as its header and its entries.
 * <p>An entry is its `* Subsystem:` line, the prose that continues it, and any
 * sub-list items under it. The marker characters and the line breaks are
 * dropped: the element says what the marker said, and the browser breaks the
 * prose where its own width needs it.
 *
 * @param {string} section - one dated block
 * @returns {{date: ?string, entries: {head: string, items: string[]}[]}} the
 *          header, null when the block has none, and the entries in document
 *          order, each with its opening prose and its sub-list items
 */
export function parseBlock(section) {
  const lines = section.split("\n");
  const date = DATE_LINE.test(lines[0]) ? lines[0] : null;
  const entries = [];
  let entry = null;
  let items = null;
  for (const line of date === null ? lines : lines.slice(1)) {
    const text = line.trim();
    if (text.length === 0) {continue;}
    if (ENTRY_LINE.test(line)) {
      entry = {head: line.replace(ENTRY_LINE, "").trim(), items: []};
      entries.push(entry);
      items = null;
    } else if (ITEM_LINE.test(line)) {
      // An item ahead of any entry is still an item, so it opens one rather
      // than being lost.
      if (entry === null) {
        entry = {head: "", items: []};
        entries.push(entry);
      }
      entry.items.push(line.replace(ITEM_LINE, "").trim());
      items = entry.items;
    } else if (entry === null) {
      // Prose history.txt writes outside the entry form - the older blocks
      // open with a version line and a paragraph - becomes an entry itself.
      entry = {head: text, items: []};
      entries.push(entry);
    } else if (items === null) {
      entry.head += " " + text;
    } else {
      items[items.length - 1] += " " + text;
    }
  }
  return {date: date, entries: entries};
}

/**
 * How many columns to lay the log out in. The theme's box has a fixed height and
 * scrolls sideways, so a column is never taller than the box, and the answer is
 * the fewest columns that hold the log inside it: fewer would overflow the box,
 * more would only widen it. An entry taller than the box cannot be cut at all,
 * so it sets the floor the columns are measured against, and the box's vertical
 * scroll takes it.
 *
 * @param {number[]} heights - the units' heights, in document order
 * @param {number} boxHeight - the height one column holds, 0 for unknown
 * @param {number} minimum - the column count the theme asks for, at least 1
 * @param {number} [chrome] - what one dated block's wrapper costs
 * @param {number[]} [starts] - 1 on the first unit of every dated block, 0 on the
 *                              rest: a column that shows part of a block shows
 *                              its header, its padding and its rule, so a run is
 *                              as tall as its units plus one wrapper per block it
 *                              holds - including a block continued from the
 *                              column before it, whose header is drawn again
 * @returns {number} the column count, at least the minimum
 */
export function columnCount(heights, boxHeight, minimum, chrome, starts) {
  const floor = Math.max(1, Math.floor(minimum) || 1);
  const wrap = chrome > 0 && starts && starts.length === heights.length ? chrome : 0;
  if (heights.length === 0 || !(boxHeight > 0)) {return floor;}
  let tallest = 0;
  for (let i = 0; i < heights.length; i++) {
    // Every unit stands in some block's wrapper, its own or one carried over.
    tallest = Math.max(tallest, heights[i] + wrap);
  }
  const limit = Math.max(boxHeight, tallest);
  // Taking every unit that still fits is the fewest runs a sequence of fixed
  // order can be cut into: a run that stops short of the limit gives up room the
  // next run could have used, so no other cut needs more runs than this one.
  let runs = 1;
  let used = 0;
  let open = false;
  for (let i = 0; i < heights.length; i++) {
    // A wrapper is charged where a block starts, and again where a block that
    // started in another column is carried into this one.
    let extra = wrap > 0 && (starts[i] || !open) ? wrap : 0;
    if (used > 0 && used + extra + heights[i] > limit) {
      runs++;
      used = 0;
      open = false;
      extra = wrap;
    }
    used += extra + heights[i];
    open = true;
  }
  return Math.max(floor, runs);
}

/**
 * Where to cut a sequence of units so the columns come out as even as the units
 * allow. The runs stay contiguous, so the log still reads down one column and
 * then the next. With a limit, the cut first minimises how far the runs overflow
 * it - a column that overflows the box is text the reader cannot reach - and only
 * then the tallest run; without one, every run overflows a limit of nothing, so
 * the cut simply minimises the tallest run, which is the dead space at the foot of
 * the shortest column and, since the box scrolls sideways by as many columns as it
 * holds, the scroll width too.
 *
 * @param {number[]} heights - the units' heights, in any unit, in document order
 * @param {number} columns - how many runs to cut; more than there are units is
 *                           reduced to the unit count
 * @param {number} [limit] - the height one run may reach, 0 for no limit
 * @param {number} [chrome] - what one dated block's wrapper costs
 * @param {number[]} [starts] - 1 on the first unit of every dated block, 0 on the
 *                              rest: a run holds one wrapper for every block it
 *                              shows, including a block carried over from the
 *                              column before it, whose header is drawn again
 * @returns {number[][]} the unit indices of each run, in document order, no run
 *                       empty
 */
export function partition(heights, columns, limit, chrome, starts) {
  const count = heights.length;
  const runs = Math.min(Math.max(1, Math.floor(columns)), count);
  const cap = limit > 0 ? limit : 0;
  const wrap = chrome > 0 && starts && starts.length === count ? chrome : 0;
  const index = [];
  for (let i = 0; i < count; i++) {index.push(i);}
  if (runs <= 1) {return [index];}
  const prefix = new Array(count + 1).fill(0);
  const blocks = new Array(count + 1).fill(0);
  for (let i = 0; i < count; i++) {
    prefix[i + 1] = prefix[i] + heights[i];
    blocks[i + 1] = blocks[i] + (starts[i] ? 1 : 0);
  }
  // What a run from unit j to unit i costs: its units, and one wrapper for every
  // block that starts inside it, and one more if it opens in the middle of a
  // block, where that block's header has to be drawn again.
  const height = (j, i) => prefix[i] - prefix[j] +
      wrap * (blocks[i] - blocks[j] + (starts[j] ? 0 : 1));
  // A cut's cost is the overflow of all its runs plus its tallest run, in that
  // order of importance, folded into one number: overflow is counted in units of
  // SCALE, far more than any run can measure, so the smaller number always
  // overflows less and, among those that overflow equally, is the shorter.
  // best[i] is the best cost for the first i units in the runs built so far,
  // tallest[i] that cut's tallest run, and cut[i] where it puts its last break.
  // One run over the first i units is as tall as all of them, so that is where
  // the table starts.
  const scale = 1e7;
  let best = new Array(count + 1).fill(0);
  let tallest = new Array(count + 1).fill(0);
  for (let i = 1; i <= count; i++) {
    tallest[i] = height(0, i);
    best[i] = (tallest[i] > cap ? tallest[i] - cap : 0) * scale + tallest[i];
  }
  const cuts = [];
  for (let run = 2; run <= runs; run++) {
    const next = new Array(count + 1).fill(Infinity);
    const nextTall = new Array(count + 1).fill(0);
    const nextCut = new Array(count + 1).fill(0);
    for (let i = run; i <= count; i++) {
      for (let j = run - 1; j < i; j++) {
        const height2 = height(j, i);
        const over = height2 > cap ? height2 - cap : 0;
        const tall = Math.max(tallest[j], height2);
        const cost = best[j] + over * scale + tall - tallest[j];
        if (cost < next[i]) {
          next[i] = cost;
          nextTall[i] = tall;
          nextCut[i] = j;
        }
      }
    }
    best = next;
    tallest = nextTall;
    cuts.push(nextCut);
  }
  // Walk the recorded breaks back from the end to get each run's start, and
  // close the sequence at the start of the first run.
  const bounds = [count];
  let end = count;
  for (let run = runs - 1; run >= 1; run--) {
    end = cuts[run - 1][end];
    bounds.push(end);
  }
  bounds.push(0);
  bounds.reverse();
  const grouped = [];
  for (let i = 0; i < bounds.length - 1; i++) {
    const run = [];
    for (let x = bounds[i]; x < bounds[i + 1]; x++) {run.push(x);}
    grouped.push(run);
  }
  return grouped;
}

/** The change log as the page first served it, read once. */
let source = null;

/** The dated blocks parsed from it. */
let blocks = [];

/**
 * The rendered height a unit takes in the layout, margins included, since the
 * gap between units is part of what a column has to hold.
 *
 * @param {HTMLElement} unit - the element to measure
 * @returns {number} its height in CSS pixels
 */
function outerHeight(unit) {
  const style = window.getComputedStyle(unit);
  return unit.getBoundingClientRect().height +
      (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0);
}

/**
 * The box the theme gives the change log, as the multi-column layout it would
 * have used: the count, the gap and the column width the theme asks for, and the
 * height one column shows at a time. Read from the element, so every theme and
 * every breakpoint's own numbers are used rather than a copy of them here.
 *
 * @param {HTMLElement} pre - the element holding the change log
 * @returns {{width: number, height: number, gap: number, columns: number,
 *            columnWidth: number}} the box's content area and the columns in it
 */
function boxMetrics(pre) {
  const style = window.getComputedStyle(pre);
  const number = function(text) {return parseFloat(text) || 0;};
  const width = pre.clientWidth - number(style.paddingLeft) - number(style.paddingRight);
  const height = pre.clientHeight - number(style.paddingTop) - number(style.paddingBottom);
  const gap = number(style.columnGap) || COLUMN_GAP;
  let columns = parseInt(style.columnCount, 10);
  if (!(columns > 0)) {
    // `columns:auto 2` gives a count; `columns:250px` gives a width instead, and
    // a box with neither is a box that was never meant to be split.
    const each = number(style.columnWidth);
    columns = each > 0 ? Math.max(1, Math.floor((width + gap) / (each + gap))) : 1;
  }
  return {width: width, height: height, gap: gap, columns: columns,
          columnWidth: (width - (columns - 1) * gap) / columns};
}

/**
 * The element for one piece of an entry: its opening prose and, when it has any,
 * a nested list of sub-list items.
 *
 * @param {?string} head - the prose, null when the piece has none
 * @param {?string[]} items - the sub-list items, null when the piece has none
 * @param {boolean} cont - true for a piece that continues a paragraph cut across
 *                         columns, which carries no marker of its own
 * @returns {HTMLElement} the unit
 */
function unitElement(head, items, cont) {
  const unit = document.createElement("div");
  unit.className = "unit";
  if (head !== null && head.length > 0) {
    const p = document.createElement("p");
    p.className = cont ? "cont" : "head";
    p.textContent = head;
    unit.appendChild(p);
  }
  if (items !== null && items.length > 0) {
    const list = document.createElement("ul");
    list.className = "items";
    for (const item of items) {
      const li = document.createElement("li");
      li.textContent = item;
      list.appendChild(li);
    }
    unit.appendChild(list);
  }
  return unit;
}

/**
 * The words a paragraph holds before it no longer fits, and the words left over.
 * <p>Measured in the column it is shown in, so the answer is the column's own:
 * the same text fits a different number of words at a different width.
 *
 * @param {HTMLElement} p - the paragraph element to measure, already in the box
 * @param {string} text - the paragraph's text
 * @param {number} limit - the height it has to fit, in CSS pixels
 * @returns {[string, string]} the part that fits and the part that does not
 */
function fitHead(p, text, limit) {
  const words = text.split(" ");
  // A word at a time, from the shortest prefix up: the paragraph is a few dozen
  // words, and measuring each is cheaper than a binary search that has to move
  // the text in and out of the element anyway.
  let used = 1;
  while (used < words.length) {
    p.textContent = words.slice(0, used + 1).join(" ");
    if (p.getBoundingClientRect().height > limit) {break;}
    used++;
  }
  return [words.slice(0, used).join(" "), words.slice(used).join(" ")];
}

/**
 * The units one entry is cut into, in document order, appended to `parent` as
 * they are measured - a height only means anything in the column the unit will
 * be shown in.
 * <p>An entry no taller than the box is one unit. One that is taller is opened
 * between its sub-list items, which are never cut themselves, and the first unit
 * keeps the opening prose with the first item so a cut entry still names itself.
 * A unit still taller than the box is one paragraph that cannot fit at any
 * height, and is broken at a word boundary, its pieces marked as continuations
 * so the entry's marker is drawn once.
 *
 * @param {{head: string, items: string[]}} entry - one parsed entry
 * @param {number} limit - the height one unit has to fit, 0 to cut nothing
 * @param {HTMLElement} parent - the column the units are measured in
 * @returns {HTMLElement[]} the units, in document order
 */
function unitElements(entry, limit, parent) {
  const whole = unitElement(entry.head, entry.items, false);
  parent.appendChild(whole);
  if (limit <= 0 || outerHeight(whole) <= limit) {return [whole];}
  return cutEntry(entry, limit, parent, whole);
}

/**
 * Cuts an entry taller than the box into units that are not, and leaves them in
 * `parent` where the whole entry stood.
 *
 * @param {{head: string, items: string[]}} entry - one parsed entry
 * @param {number} limit - the height one unit has to fit, in CSS pixels
 * @param {HTMLElement} parent - the column the units are measured in
 * @param {HTMLElement} whole - the unit that does not fit, already in `parent`
 * @returns {HTMLElement[]} the units, in document order
 */
function cutEntry(entry, limit, parent, whole) {
  let parts = [];
  if (entry.items.length > 1) {
    parent.removeChild(whole);
    parts.push(unitElement(entry.head, entry.items.slice(0, 1), false));
    for (let i = 1; i < entry.items.length; i++) {
      parts.push(unitElement(null, entry.items.slice(i, i + 1), false));
    }
  } else {
    parts = [whole];
  }
  const units = [];
  for (const part of parts) {
    parent.appendChild(part);
    if (outerHeight(part) <= limit) {units.push(part); continue;}
    const head = part.querySelector(".head");
    const list = part.querySelector(".items");
    if (head === null) {units.push(part); continue;}
    // What is left for the paragraph once the sub-list sharing its unit has been
    // accounted for. A sub-list taller than the box on its own is left alone:
    // its items are the smallest thing the log is made of.
    const budget = list === null ? limit :
        limit - (outerHeight(part) - head.getBoundingClientRect().height);
    // A paragraph is only worth breaking across columns if a piece of it is a
    // few lines long: one line at the foot of a column and the rest of the
    // sentence in the next is harder to read than a column that runs over.
    let line = parseFloat(window.getComputedStyle(head).lineHeight) || 0;
    if (line > 0 && line < 4) {
      // a multiplier, not a length: `line-height:1.5` computes to 1.5
      line *= parseFloat(window.getComputedStyle(head).fontSize) || 0;
    }
    if (!(budget > 0) || (line > 0 && budget < line * 3)) {units.push(part); continue;}
    // The paragraph element stays in the column while it is being measured
    // against, and the pieces replace it once they are all cut.
    const pieces = [];
    let text = head.textContent;
    let first = true;
    for (;;) {
      const piece = fitHead(head, text, budget);
      pieces.push(unitElement(piece[0], null, !first));
      first = false;
      if (piece[1].length === 0) {break;}
      text = piece[1];
    }
    if (list !== null) {
      // The paragraph's last piece and the sub-list belong together.
      pieces[pieces.length - 1].appendChild(list);
    }
    for (const piece of pieces) {parent.insertBefore(piece, part);}
    parent.removeChild(part);
    for (const piece of pieces) {piece.dataset.block = part.dataset.block;}
    units.push(...pieces);
  }
  return units;
}

/**
 * Lays every unit of the log into the first of `columns` columns, so each is
 * measured at the width it will be shown at, and returns the units in document
 * order.
 *
 * @param {HTMLElement} box - the element holding the columns
 * @param {number} columns - how many columns to size
 * @param {number} columnWidth - the width each column has, in CSS pixels
 * @param {number} gap - the space between two columns, in CSS pixels
 * @param {number} tallest - the height one column holds, 0 to leave every entry
 *                           whole; an entry taller than it is opened between its
 *                           items, because it, and not the cut, is what makes a
 *                           column overflow the box
 * @returns {HTMLElement[]} the units, in document order, each tagged with the
 *                          block it came from
 */
function layout(box, columns, columnWidth, gap, tallest) {
  box.textContent = "";
  box.style.gap = "0 " + gap + "px";
  // The gap is what the theme's rule between columns is centred in.
  box.style.setProperty("--changelog-gap", gap + "px");
  const first = document.createElement("div");
  first.className = "changelog-col";
  first.style.width = columnWidth + "px";
  box.appendChild(first);
  for (let i = 1; i < columns; i++) {
    const empty = document.createElement("div");
    empty.className = "changelog-col";
    empty.style.width = columnWidth + "px";
    box.appendChild(empty);
  }
  const units = [];
  for (let b = 0; b < blocks.length; b++) {
    for (const entry of blocks[b].entries) {
      // Measured where it will be shown, because that is the only width at
      // which "taller than the column" means anything.
      for (const part of unitElements(entry, tallest, first)) {
        part.dataset.block = String(b);
        units.push(part);
      }
    }
  }
  return units;
}

/**
 * Moves the measured units into their columns, each block's entries inside a
 * container that carries the block's dated header, so a block spread over two
 * columns is headed in both rather than once and a half.
 *
 * @param {HTMLElement} box - the element holding the columns
 * @param {HTMLElement[]} units - the units in document order, tagged by block
 * @param {number[][]} runs - which units go in which column
 * @returns {void}
 */
function distribute(box, units, runs) {
  const columns = [...box.children];
  for (const column of columns) {column.textContent = "";}
  for (let r = 0; r < runs.length; r++) {
    const column = columns[r] || columns[columns.length - 1];
    let block = null;
    let list = null;
    for (const index of runs[r]) {
      const unit = units[index];
      const id = unit.dataset.block;
      if (block === null || block.dataset.block !== id) {
        block = document.createElement("div");
        block.className = "block";
        block.dataset.block = id;
        // The page shows only the head of history.txt, so the last block is cut
        // off mid-entry. The themes hide it, and this is the rebuilt page's last
        // block, so it is marked for the same rule.
        if (Number(id) === blocks.length - 1) {block.classList.add("tail");}
        const date = document.createElement("div");
        date.className = "date";
        date.textContent = blocks[Number(id)].date || "";
        block.appendChild(date);
        list = document.createElement("div");
        list.className = "entries";
        block.appendChild(list);
        column.appendChild(block);
      }
      list.appendChild(unit);
    }
  }
  // A column with nothing in it would still take its share of the scroll width,
  // and the last block in one needs no rule under it: the rule separates two
  // blocks of the same column, and there is nothing below it to separate from.
  for (const column of columns) {
    if (column.children.length === 0) {column.remove(); continue;}
    const shown = [...column.querySelectorAll(".block")].filter(function(block) {
      return !block.classList.contains("tail");
    });
    const last = shown[shown.length - 1];
    if (last !== undefined) {last.classList.add("last");}
  }
}

/**
 * The height the content of one column takes, which is not the height the column
 * itself has: the columns are stretched to the box so the rule between them runs
 * its full height whatever is in them.
 *
 * @param {HTMLElement} column - the column to measure
 * @returns {number} its content's height in CSS pixels, 0 when it is empty
 */
function contentHeight(column) {
  const top = column.getBoundingClientRect().top;
  let bottom = top;
  for (const child of column.children) {
    bottom = Math.max(bottom, child.getBoundingClientRect().bottom);
  }
  return bottom - top;
}

/**
 * The tallest column the box is holding, which is how far the log overflows it.
 *
 * @param {HTMLElement} box - the element holding the columns
 * @returns {number} its height in CSS pixels, 0 when the box is empty
 */
function tallestColumn(box) {
  let tall = 0;
  for (const column of box.children) {
    tall = Math.max(tall, contentHeight(column));
  }
  return tall;
}

/**
 * Which units open a dated block: a block's header, padding and rule are drawn in
 * every column its entries appear in, so where a block starts in the sequence is
 * what tells the cut what each run costs.
 *
 * @param {HTMLElement[]} units - the units in document order, tagged by block
 * @returns {number[]} 1 on the first unit of a block, 0 on the rest
 */
function blockStarts(units) {
  const starts = [];
  let previous = null;
  for (const unit of units) {
    const id = unit.dataset.block;
    starts.push(id === previous ? 0 : 1);
    previous = id;
  }
  return starts;
}

/**
 * Lays the log out at one count and measures what that came to.
 *
 * @param {HTMLElement} pre - the element holding the change log
 * @param {number} columns - how many columns to cut
 * @param {{columnWidth: number, gap: number}} box - the column width and the
 *        space between two columns the theme's box gives
 * @param {number} chrome - what one dated block's wrapper costs, charged to every
 *                          block a run holds
 * @param {number} cap - the height one run may reach
 * @returns {{heights: number[], starts: number[]}} the units' heights and which
 *          of them open a block, both in document order
 */
function place(pre, columns, box, chrome, cap) {
  // The rule between columns is as tall as the box, whatever the columns come to.
  pre.style.setProperty("--changelog-rule", Math.round(box.height) + "px");
  const units = layout(pre, columns, box.columnWidth, box.gap, Math.max(1, cap - chrome));
  const heights = units.map(outerHeight);
  const starts = blockStarts(units);
  distribute(pre, units, partition(heights, columns, cap, chrome, starts));
  return {heights, starts};
}

/**
 * How many columns overflow the box, which is what the layout is trying to reach
 * before anything else: one column over it is a box that scrolls up and down as
 * well as sideways, and the sideways scroll is the whole point of the layout.
 *
 * @param {HTMLElement} box - the element holding the columns
 * @param {number} height - the height one column holds
 * @returns {number} how many columns are taller than that
 */
function overColumns(box, height) {
  let over = 0;
  for (const column of box.children) {
    if (contentHeight(column) > height + 1) {over++;}
  }
  return over;
}

/**
 * Rebuilds the change log as the columns the box can hold, each one a
 * contiguous run of whole entries of near-equal height.
 *
 * @returns {void}
 */
function reflow() {
  const pre = document.querySelector("#changelog pre");
  if (!pre) {return;}
  // The rebuilt layout replaces the text, so the text to parse is the text as
  // served, kept from the first pass.
  if (source === null) {source = pre.textContent;}
  if (blocks.length === 0) {blocks = splitSections(source).map(parseBlock);}
  if (blocks.length === 0) {return;}
  // The class is what makes the box lay its children out as columns and lets the
  // prose wrap, so it goes on once the theme's own multi-column numbers have been
  // read, while the box is still a multi-column box.
  let box = boxMetrics(pre);
  pre.classList.add("reflowed");
  // A box the theme splits sideways is capped at the box's height and continued
  // into as many columns as that needs, which is what its sideways scroll is for.
  // A box the theme leaves single is allowed to run down past the box instead,
  // which is what its vertical scroll is for, and no entry in it is opened up.
  const sideways = box.columns > 1;
  pre.classList.toggle("sideways", sideways);
  if (!sideways) {
    place(pre, box.columns, box, 0, box.height);
    return;
  }
  // The count sets the width every unit is measured at, so the layout is settled
  // by cutting, measuring what the cut actually came to, and cutting again with
  // the count that measurement asks for. What is measured is the column, not the
  // units in it: a block's dated header, its padding and its rule are repeated in
  // every column its entries appear in, and they are none of them part of a
  // unit's height, so what one of them costs is worked out from the difference
  // and charged to the next cut wherever that cut starts or carries a block over.
  //
  // The cut is asked for a little inside the box, because the height it models is
  // the units and the wrappers it knows about, and what the browser paints is a
  // pixel or two more: that margin is the difference between a box that only
  // scrolls sideways and one that has picked up a vertical scrollbar.
  let columns = box.columns;
  let chrome = 0;
  let cap = box.height - LAYOUT_SLACK_PX;
  let best = null;
  for (let pass = 0; pass < LAYOUT_PASSES; pass++) {
    // Read again each pass: the box is not the same width once it is laying
    // itself out as columns, and a column that is a few pixels narrower is a
    // column that is a little taller.
    box = boxMetrics(pre);
    const laid = place(pre, columns, box, chrome, cap);
    const over = overColumns(pre, box.height);
    const tall = tallestColumn(pre);
    if (best === null || over < best.over || (over === best.over && tall < best.tall)) {
      best = {columns, chrome, cap, over, tall};
    }
    if (over === 0) {return;}
    let total = 0;
    for (const height of laid.heights) {total += height;}
    let measured = 0;
    for (const column of pre.children) {measured += contentHeight(column);}
    const instances = pre.querySelectorAll(".block").length;
    // What one dated block's wrapper costs - its header, its padding and its
    // rule - charged once to every column that shows part of the block.
    chrome = instances > 0 ? Math.max(0, (measured - total) / instances) : 0;
    const next = Math.min(laid.heights.length,
        columnCount(laid.heights, cap, columns + 1, chrome, laid.starts));
    if (next === columns) {break;}
    columns = next;
  }
  // The passes only ever guess; keep the layout that overflowed the box least
  // rather than the last guess that got there.
  if (best !== null && (best.columns !== columns || best.cap !== cap)) {
    box = boxMetrics(pre);
    place(pre, best.columns, box, best.chrome, best.cap);
  }
}

/** A pending reflow timer, or 0 when none is queued. */
let pending = 0;

if (typeof document !== "undefined") {
  reflow();
  if (typeof window !== "undefined") {
    window.addEventListener("resize", function() {
      if (pending) {window.clearTimeout(pending);}
      pending = window.setTimeout(function() {
        pending = 0;
        reflow();
      }, REFLOW_DEBOUNCE_MS);
    });
  }
}
