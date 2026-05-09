/**
 * @module markdown
 * @file SusiMail Markdown renderer.
 * Converts Markdown-formatted mail bodies into HTML.
 * Supports: CommonMark + GFM (tables, strikethrough, task lists, fenced code lang).
 * Self-contained, no external dependencies.
 *
 * Security model:
 *   - Renderer emits only a fixed set of whitelisted HTML tags.
 *   - All link URLs pass through scheme allowlist (http:, https:, mailto:).
 *   - All text content is HTML-escaped before output.
 *
 * Security note:
 *   initMarkdown() expects mailbody elements to contain plain text markdown.
 *   Do not pre-populate with HTML. For defense-in-depth, consider post-processing
 *   output with DOMPurify if handling untrusted third-party input.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @typedef {Object} Block
 * @property {string} type - Block type
 * @property {number} [level] - Heading level (1-6)
 * @property {string} [content] - Inner content (HTML)
 * @property {string} [rawContent] - Raw text (pre-inline)
 * @property {string} [lang] - Code language
 * @property {Block[]} [blocks] - Sub-blocks
 * @property {string[]} [rows] - Table rows
 * @property {boolean} [ordered] - Ordered list flag
 * @property {string} [bullet] - List bullet
 * @property {boolean} [taskChecked] - Task list checked state
 */

/**
 * CSS class names for rendered elements.
 * @type {Object<string, string|function(number):string>}
 */
const CLS = Object.freeze({
  LINK:       "md link",
  AUTOLINK:   "md link autolink",
  IMAGE:      "md image",
  CODE:       "md code",
  CODEBLOCK:  "md codeblock",
  EM:         "md em",
  STRONG:     "md strong",
  DEL:        "md del",
  PARAGRAPH:  "md paragraph",
  BLOCKQUOTE: "md blockquote",
  RULE:       "md rule",
  TABLE:      "md table",
  THEAD:      "md thead",
  TBODY:      "md tbody",
  TH:         "md th",
  TD:         "md td",
  TR:         "md tr",
  OL:         "md ordered-list",
  UL:         "md unordered-list",
  LIST_ITEM:  "md list-item",
  HEADING:    (level) => `md heading h${level}`,
});

/** @type {string[]} Allowed URL schemes for links */
const _ALLOWED_SCHEMES = Object.freeze(["http:", "https:", "mailto:"]);
/** @type {string} Characters that need escaping in markdown */
const _ESCAPE_CHARS = "\\`*{}[]()>#+.!-_|";
/** @type {RegExp} Trailing double-space for hard line break */
const _BR_REGEX = /  $/;
/** @type {string} Placeholder for <br> to avoid early escaping */
const _BR_PLACEHOLDER = "\x02BR\x02";

/** @type {Object<string, RegExp>} Regular expressions for parsing */
const RE_BLANK = /^\s*$/;
const RE_HR = /^(?:[*\-+_]\s*){3,}(\s+[\d.]+)?\s*$/;         // Horizontal rule
const RE_ATX_H = /^(#{1,6})\s+(.*)$/;                        // Atx heading
const RE_SETEXT_A = /^(.+)\n\s*=+\s*$/;                      // Setext h1
const RE_SETEXT_B = /^(.+)\n\s*-+\s*$/;                      // Setext h2
const RE_FENCED = /^(`{3,}|~{3,})\s*(\S*)/;                  // Fenced code
const RE_CODE_INDENT = /^(?: {1,4}|\t)([^\n]+)/;             // Indented code
const RE_LIST_U = /^([*+-])((?:\s+[^\n]*)?)$/;               // Unordered list
const RE_LIST_O = /^(\d+\.)((?:\s+[^\n]*)?)$/;               // Ordered list
const RE_LIST_LAZY = /^([*+-]|\d+\.)\s+/;                    // Lazy list cont
const RE_DEF = /^\[([^\]]+)\]:\s+(<[^>]+>|[^\s]+)(.*)/;      // Link def
const RE_TASK = /^\s*\[([xX\*\-])\]\s+(.*)/;                 // Task list
const RE_TABLE_ROW = /^(\|[^\n]+\|)\s*$/;                    // Table row
const RE_MARKDOWN_INDICATOR = /^[*_#`>\[\]|+-]/;             // MD indicator
const RE_BLOCKQUOTE = /^>/;                                  // Blockquote
const RE_TABLE_SEP = /^\|[\s*:=-]+(\|[\s*:=-]+)+\|\s*$/;     // Table sep
const RE_IMG = /!\[([^\]]*)\]\(([^)]+)\)/;                   // Image
const RE_LINK = /\[([^\]]+)\]\(([^)]+)\)/;                   // Link
const RE_LINK_REF = /\[([^\]]+)\]\[([^\]]*)\]/;              // Link ref
const RE_LINK_REF_EMPTY = /\[([^\]]+)\]\[\s*\]/;             // Link ref empty
const RE_AUTO_LINK = /<(https?:\/\/[^>]+)>/;                 // Autolink
const RE_SCHEME = /^([a-z][a-z0-9+.-]*):/i;                  // URL scheme

/**
 * HTML-escapes a string for attribute values.
 * @param {string} str - Input string
 * @returns {string} Escaped string
 */
const _esc = (str) => str
  .replace(/&/g, "&amp;")
  .replace(/</g, "&lt;")
  .replace(/>/g, "&gt;")
  .replace(/"/g, "&quot;");

/**
 * HTML-escapes a string for element content.
 * @param {string} str - Input string
 * @returns {string} Escaped string
 */
const _ent = (str) => str
  .replace(/&/g, "&amp;")
  .replace(/</g, "&lt;")
  .replace(/>/g, "&gt;");

/**
 * Checks if a URL uses an allowed scheme.
 * @param {string} url - URL to check
 * @returns {boolean} True if URL is safe
 */
const _safeUrl = (url) => {
  const trimmed = url.trim();
  if (trimmed.startsWith("//")) return false;
  if (trimmed.length !== trimmed.replace(/[\x00-\x1f\x7f]/g, "").length) return false;
  if (/%[0-9a-f]{2}/i.test(trimmed)) {
    try {
      const decoded = decodeURIComponent(trimmed);
      if (/[<>"\s]/.test(decoded)) return false;
    } catch (e) { return false; }
  }
  const m = trimmed.match(RE_SCHEME);
  if (!m) return /^[a-zA-Z][a-zA-Z0-9.+-]*:/i.test(trimmed) === false;
  return _ALLOWED_SCHEMES.includes(m[1].toLowerCase());
};

/**
 * Converts tabs to spaces (4-column tab stops).
 * @param {string} line - Input line
 * @returns {string} Line with tabs replaced
 */
const _tab2space = (line) => {
  const out = [];
  let ti = 0;
  for (let j = 0; j < line.length; j++) {
    const c = line[j];
    if (c === "\t") {
      out.push(" ".repeat(4 - (ti % 4)));
      ti = 0;
    } else {
      out.push(c);
      ti++;
    }
  }
  return out.join("");
};

/**
 * Skips leading whitespace, returns indent and text.
 * @param {string} line - Input line
 * @returns {{indent: number, text: string}} Indent and trimmed text
 */
const _skip = (line) => {
  let i = 0;
  const len = line.length;
  while (i < len && /\s/.test(line[i])) i++;
  return { indent: i, text: line.substring(i) };
};

/**
 * Finds closing delimiter for emphasis (bold/italic).
 * @param {string} src - Source text
 * @param {number} start - Start position
 * @param {number} len - Source length
 * @param {string} close - Closing character
 * @returns {number} Position of closing delimiter, or -1
 */
const _findEm = (src, start, len, close) => {
  for (let i = start; i < len; i++) {
    const c = src[i];
    if (c === " " || c === "\t" || c === "\u00a0") continue;
    if (c !== close) continue;
    if (i + 1 < len && src[i + 1] === close) {
      let before = i - 1;
      while (before >= start && (src[before] === " " || src[before] === "\t")) before--;
      if (before >= start) return i;
    }
    return i;
  }
  return -1;
};

const _a = (href, text, cls) => {
  const safeHref = _safeUrl(href) ? _esc(href) : "#blocked";
  const isExternal = /^https?:/i.test(href);
  const rel = isExternal ? ' rel="noopener noreferrer"' : "";
  return `<a class="${cls}" href="${safeHref}"${rel}>${text}</a>`;
};

/**
 * Creates an image element with URL safety check.
 * @param {string} src - Image source URL
 * @param {string} alt - Alt text
 * @returns {string} Image HTML
 */
const _img = (src, alt) => {
  const safeSrc = _safeUrl(src) ? _esc(src) : "";
  return `<img class="${CLS.IMAGE}" src="${safeSrc}" alt="${_esc(alt)}" />`;
};

/**
 * Parses inline markdown elements and returns HTML.
 * @param {string} src - Source text
 * @param {Object} defs - Link reference definitions
 * @returns {string} Rendered HTML
 */
const _inlines = (src, defs) => {
  const out = [];
  let pos = 0;
  const len = src.length;

  while (pos < len) {
    const ch = src[pos];

    if (ch === "`") {
      const end = src.indexOf("`", pos + 1);
      if (end === -1) { out.push(_ent(ch)); pos++; continue; }
      out.push(`<code class="${CLS.CODE}">${_ent(src.substring(pos + 1, end))}</code>`);
      pos = end + 1;
    } else if (ch === "!" && src[pos + 1] === "[") {
      const m = RE_IMG.exec(src.substring(pos));
      if (m) {
        out.push(_img(m[2], m[1]));
        pos += m[0].length;
        continue;
      }
      out.push(_ent(ch)); pos++;
    } else if (ch === "[") {
      let matched = false;
      let m = RE_LINK.exec(src.substring(pos));
      if (m) {
        out.push(_a(m[2], _ent(m[1]), CLS.LINK));
        pos += m[0].length;
        matched = true;
      }
      if (!matched) {
        m = RE_LINK_REF.exec(src.substring(pos));
        if (m) {
          const key = (m[2] || m[1]).toLowerCase();
          if (defs[key]) {
            out.push(_a(defs[key][0], _ent(m[1]), CLS.LINK));
          } else {
            out.push(_ent(src.substring(pos, pos + m[0].length)));
          }
          pos += m[0].length;
          matched = true;
        }
      }
      if (!matched) {
        m = RE_LINK_REF_EMPTY.exec(src.substring(pos));
        if (m) {
          const key = m[1].toLowerCase();
          if (defs[key]) {
            out.push(_a(defs[key][0], _ent(m[1]), CLS.LINK));
          } else {
            out.push(_ent(src.substring(pos, pos + m[0].length)));
          }
          pos += m[0].length;
          matched = true;
        }
      }
      if (!matched) { out.push(_ent(ch)); pos++; }
    } else if (ch === "<") {
      const m = RE_AUTO_LINK.exec(src.substring(pos));
      if (m) {
        out.push(_a(m[1], _ent(m[1]), CLS.AUTOLINK));
        pos += m[0].length;
        continue;
      }
      const end = src.indexOf(">", pos + 1);
      if (end !== -1) { out.push(_ent(src.substring(pos, end + 1))); pos = end + 1; continue; }
      out.push(_ent(ch)); pos++;
    } else if (ch === "*" || ch === "_") {
      const c1 = src[pos + 1];
      const c2 = src[pos + 2];
      if (c1 === ch && c2 === ch) {
        const end = _findEm(src, pos + 3, len, ch === "*" ? "*" : "_");
        if (end !== -1) {
          out.push(`<strong class="${CLS.STRONG}"><em class="${CLS.EM}">${_ent(src.substring(pos + 3, end))}</em></strong>`);
          pos = end + 2;
          continue;
        }
      }
      if (c1 === ch) {
        const end = _findEm(src, pos + 2, len, ch === "*" ? "*" : "_");
        if (end !== -1) {
          out.push(`<strong class="${CLS.STRONG}">${_ent(src.substring(pos + 2, end))}</strong>`);
          pos = end + 1;
          continue;
        }
      }
      const end = _findEm(src, pos + 1, len, ch);
      if (end !== -1) {
        out.push(`<em class="${CLS.EM}">${_ent(src.substring(pos + 1, end))}</em>`);
        pos = end + 1;
        continue;
      }
      out.push(_ent(ch)); pos++;
    } else if (ch === "~" && src[pos + 1] === "~") {
      const end = src.indexOf("~~", pos + 2);
      if (end !== -1) {
        out.push(`<del class="${CLS.DEL}">${_ent(src.substring(pos + 2, end))}</del>`);
        pos = end + 2;
        continue;
      }
      out.push(_ent(ch)); pos++;
    } else if (ch === "\\" && pos + 1 < len) {
      const nn = src[pos + 1];
      if (_ESCAPE_CHARS.includes(nn)) { out.push(_ent(nn)); pos += 2; continue; }
      out.push(_ent(ch), _ent(nn)); pos += 2; continue;
    } else {
      out.push(_ent(ch)); pos++;
    }
  }
  return out.join("");
};

/**
 * Renders a single block to HTML.
 * @param {Block} b - Block object
 * @returns {string} Rendered HTML
 */
const _renderBlock = (b) => {
  switch (b.type) {
    case "heading":
      return `<h${b.level} class="${CLS.HEADING(b.level)}">${b.content}</h${b.level}>`;
    case "hr":
      return `<hr class="${CLS.RULE}" />`;
    case "blockquote": {
      const inner = b.blocks.filter((blk) => blk.type !== "blank").map(_renderBlock).join("");
      return `<blockquote class="${CLS.BLOCKQUOTE}">${inner}</blockquote>`;
    }
    case "code": {
      const langCls = b.lang ? ` language-${b.lang}` : "";
      return `<pre class="${CLS.CODEBLOCK}"><code class="${CLS.CODE}${langCls}">${b.content}</code></pre>`;
    }
    case "table":
      const tbl = _renderTable(b);
      return tbl;
    case "listitem":
      return b.content;
    case "paragraph":
      return `<p class="${CLS.PARAGRAPH}">${b.content}</p>`;
    case "listgroup":
      return b.content;
    case "list":
      const tag = b.ordered ? "ol" : "ul";
      const cls = b.ordered ? CLS.OL : CLS.UL;
      const items = b.items.map((item) => `<li class="${CLS.LIST_ITEM}">${item}</li>`).join("");
      return `<${tag} class="${cls}">${items}</${tag}>`;
    case "blank":
      return "";
    default:
      return b.content ? _ent(b.content) : "";
  }
};

/**
 * Renders a table block to HTML.
 * @param {Block} b - Table block object
 * @returns {string} Table HTML
 */
const _renderTable = (b) => {
  const { rows } = b;
  if (rows.length === 0) return "";

  let headerRow = null;
  const dataRows = [];
  for (const row of rows) {
    if (row.match(RE_TABLE_SEP)) continue;
    if (!headerRow) headerRow = row;
    else dataRows.push(row);
  }
  if (!headerRow) return "";

  const hCells = _splitCells(headerRow);
  const hOut = hCells.map((cell) => `<th class="${CLS.TH}">${_inline(cell)}</th>`).join("");
  let html = `<table class="${CLS.TABLE}"><thead class="${CLS.THEAD}"><tr>${hOut}</tr></thead>`;

  if (dataRows.length > 0) {
    const bodyOut = dataRows.map((row) => {
      const cells = _splitCells(row);
      const cellOut = cells.map((cell) => `<td class="${CLS.TD}">${_inline(cell)}</td>`).join("");
      return `<tr class="${CLS.TR}">${cellOut}</tr>`;
    }).join("");
    html += `<tbody class="${CLS.TBODY}">${bodyOut}</tbody>`;
  }
  html += `</table>`;
  return html;
};

/**
 * Splits a table row into cells, handling escaped pipes.
 * @param {string} row - Table row string
 * @returns {string[]} Array of cell contents
 */
const _splitCells = (row) => {
  let escaped = row.replace(/\|\|/g, "\x01").replace(/\\\|/g, "\x00");
  let cells = escaped.split("|");
  if (cells.length > 0 && cells[0].trim() === "") cells.shift();
  if (cells.length > 0 && cells[cells.length - 1].trim() === "") cells.pop();
  return cells.map((c) => c.replace(/^\s+|\s+$/g, "").replace(/^\x01|\x01$/g, "").replace(/\x00/g, "|").replace(/\x01/g, "|"));
};

/**
 * Wrapper for _inlines that handles trailing double-space as br.
 * @param {string} text - Source text
 * @param {Object} defs - Link reference definitions
 * @returns {string} Rendered HTML
 */
const _inline = (text, defs = {}) => {
  const withPlaceholder = text.replace(_BR_REGEX, _BR_PLACEHOLDER);
  const rendered = _inlines(withPlaceholder, defs);
  return rendered.replace(new RegExp(_BR_PLACEHOLDER, "g"), "<br>");
};

/**
 * Attempts to convert a paragraph into a numbered list.
 * @param {string} text - Paragraph text
 * @returns {string[]|null} Array of list items, or null
 */
const _tryConvertParagraphToList = (text) => {
  if (!text || text.length < 10) return null;
  if (!/^1\.\s/.test(text)) return null;
  const items = text.split(/(\d+\.\s)/);
  if (items.length < 5) return null;
  const result = [];
  let current = "";
  for (const part of items) {
    if (/^\d+\.\s$/.test(part)) {
      if (current.trim()) result.push(current.trim());
      current = "";
    } else {
      current += part;
    }
  }
  if (current.trim()) result.push(current.trim());
  return result.length >= 3 ? result : null;
};

/**
 * Finalizes a pending block, converting paragraphs to lists if detected.
 * @param {Object} pending - Pending block object
 * @param {Object} defs - Link reference definitions
 * @returns {Object|null} Finalized block
 */
const _processPendingBlock = (pending, defs) => {
  if (!pending) return null;
  if (pending.type === "paragraph" && pending.rawContent !== undefined) {
    const listItems = _tryConvertParagraphToList(pending.rawContent);
    if (listItems) {
      pending.type = "list";
      pending.ordered = true;
      pending.items = listItems.map((item) => {
        const subBlocks = _parseBlocks([item], 0, 1, defs);
        return subBlocks.filter((b) => b.type !== "blank").map(_renderBlock).join("");
      });
      delete pending.rawContent;
    } else {
      pending.content = _inline(pending.rawContent, defs);
      delete pending.rawContent;
    }
  }
  return pending;
};

/**
 * Parses markdown text into block structure.
 * @param {string[]} lines - Input lines
 * @param {number} start - Start line index
 * @param {number} end - End line index
 * @param {Object} refdefs - Link reference definitions
 * @returns {Block[]} Array of blocks
 */
const _parseBlocks = (lines, start, end, refdefs = {}) => {
  const blocks = [];
  let inFence = false;
  let fenceEnd = null;
  let fenceLen = 0;
  let pendingDef = null;
  let i = start;

  while (i < end) {
    const line = lines[i];
    const { text } = _skip(line);
    const trimmed = text.replace(/\s+$/, "");

    if (inFence) {
      const closeMatch = trimmed.match(/^([`~])\1{2,}/);
      if (closeMatch && closeMatch[1] === fenceEnd[0] && closeMatch[0].length >= fenceLen) {
        inFence = false;
        fenceEnd = null;
        fenceLen = 0;
      } else if (pendingDef) {
        pendingDef.content += `\n${_ent(line)}`;
      }
      i++;
      continue;
    }

    if (trimmed.match(RE_BLANK)) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      blocks.push({ type: "blank" });
      i++;
      continue;
    }

    let m = trimmed.match(RE_FENCED);
    if (m) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      inFence = true;
      fenceEnd = m[1];
      fenceLen = fenceEnd.length;
      const lang = (m[2] || "").trim();
      pendingDef = { type: "code", lang, content: "" };
      i++;
      continue;
    }

    if (trimmed.match(RE_HR)) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      blocks.push({ type: "hr" });
      i++;
      continue;
    }

    m = trimmed.match(RE_ATX_H);
    if (m) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      blocks.push({ type: "heading", level: m[1].length, content: _inline(m[2] || "", refdefs) });
      i++;
      continue;
    }

    const nextLine = i + 1 < end ? _skip(lines[i + 1]).text.trim() : "";
    if (nextLine.match(/^=+\s*$/)) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      blocks.push({ type: "heading", level: 1, content: _inline(trimmed, refdefs) });
      i += 2;
      continue;
    }
    if (nextLine.match(/^-+\s*$/)) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      blocks.push({ type: "heading", level: 2, content: _inline(trimmed, refdefs) });
      i += 2;
      continue;
    }

    m = trimmed.match(RE_DEF);
    if (m) {
      const key = m[1].toLowerCase();
      const url = m[2].startsWith("<") ? m[2].slice(1, -1) : m[2];
      if (_safeUrl(url)) {
        refdefs[key] = [url, m[3] || ""];
      }
      i++;
      continue;
    }

    m = trimmed.match(RE_TABLE_ROW);
    if (m) {
      const last = blocks[blocks.length - 1];
      if (!(last && last.type === "table")) {
        blocks.push({ type: "table", rows: [] });
      }
      blocks[blocks.length - 1].rows.push(trimmed);
      i++;
      continue;
    }

    if (trimmed.match(RE_CODE_INDENT)) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      const code = _ent(trimmed.replace(/^(?: {1,4}| ?\t)/, ""));
      pendingDef = { type: "code", lang: "", content: code };
      i++;
      continue;
    }

    if (trimmed[0] === ">") {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      const bqLines = [];
      let li = i + 1;
      let bqDepth = 0;
      while (text[bqDepth] === ">") bqDepth++;
      bqLines.push(text.substring(bqDepth).trim());

      while (li < end) {
        const nxt = _skip(lines[li]).text.replace(/\s+$/, "");
        if (nxt.match(RE_BLANK)) break;
        let nbqDepth = 0;
        while (nxt[nbqDepth] === ">") nbqDepth++;
        if (nbqDepth < bqDepth || (nbqDepth === bqDepth && nxt[nbqDepth] !== ">")) break;
        bqLines.push(nxt.substring(bqDepth).trim());
        li++;
      }
      blocks.push({ type: "blockquote", blocks: _parseBlocks(bqLines, 0, bqLines.length, refdefs) });
      i = li;
      continue;
    }

    m = trimmed.match(RE_LIST_U) || trimmed.match(RE_LIST_O);
    if (m) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      const ordered = !!trimmed.match(RE_LIST_O);
      const body = m[2] || "";
      const taskM = body.match(RE_TASK);
      let taskChecked = false;
      let taskText = body;
      if (taskM) {
        const cb = taskM[1].trim();
        taskChecked = /^[xX]$/.test(cb);
        taskText = taskM[2];
      }

      const itemLines = taskText ? [taskText] : [];
      let li2 = i + 1;

      while (li2 < end) {
        const nxt2 = _skip(lines[li2]).text.replace(/\s+$/, "");
        if (nxt2.match(RE_BLANK)) { break; }
        const nxtM = nxt2.match(RE_LIST_LAZY);
        if (!nxtM) break;

        const nxtBullet = nxt2.match(RE_LIST_U);
        const nxtOrd = nxt2.match(RE_LIST_O);
        const nxtOrdered = !!nxtOrd;
        const nxtBulletChar = nxtOrd ? nxtOrd[1] : (nxtBullet ? nxtBullet[1] : "");
        if (nxtOrdered !== ordered || (nxtBullet && nxtBulletChar !== m[1])) break;

        const rest = nxt2.replace(RE_LIST_LAZY, "");
        const tsk2 = rest.match(RE_TASK);
        if (tsk2) {
          itemLines.push(tsk2[2]);
        } else {
          itemLines.push(rest);
        }
        li2++;
      }

      const subBlocks2 = _parseBlocks(itemLines, 0, itemLines.length, refdefs);
      const inner = subBlocks2.filter((blk) => blk.type !== "blank").map(_renderBlock).join("");
      blocks.push({ type: "listitem", ordered, bullet: m[1], taskChecked, content: inner });
      i = li2;
      continue;
    }

    m = trimmed.match(RE_TASK);
    if (m && !trimmed.match(RE_LIST_U) && !trimmed.match(RE_LIST_O)) {
      if (pendingDef) {
        pendingDef = _processPendingBlock(pendingDef, refdefs);
        blocks.push(pendingDef);
        pendingDef = null;
      }
      const cb = m[1].trim();
      blocks.push({ type: "listitem", ordered: false, bullet: "-", taskChecked: /^[xX]$/.test(cb), content: _inline(m[2], refdefs) });
      i++;
      continue;
    }

    if (pendingDef && pendingDef.type === "paragraph") {
      pendingDef.rawContent += `\n${line}`;
    } else if (pendingDef && pendingDef.type === "code") {
      blocks.push(pendingDef);
      pendingDef = { type: "paragraph", rawContent: line, content: "" };
    } else {
      pendingDef = { type: "paragraph", rawContent: line, content: "" };
    }
    i++;
  }

  if (pendingDef) {
    pendingDef = _processPendingBlock(pendingDef, refdefs);
    blocks.push(pendingDef);
  }
  return blocks;
};

/**
 * Groups consecutive list items into list blocks.
 * @param {Block[]} blocks - Array of blocks
 * @returns {Block[]} Grouped blocks
 */
const _groupBlocks = (blocks) => {
  const out = [];
  let i = 0;

  while (i < blocks.length) {
    const b = blocks[i];

    if (b.type === "listitem") {
      const items = [];
      const ordered = b.ordered;
      const bullet = b.bullet;
      while (i < blocks.length && blocks[i].type === "listitem" &&
        blocks[i].ordered === ordered && (ordered || blocks[i].bullet === bullet)) {
        items.push(blocks[i]);
        i++;
      }

      const tag = ordered ? "ol" : "ul";
      const cls = ordered ? CLS.OL : CLS.UL;
      const itemOut = items.map((item) => {
        const taskAttr = item.taskChecked ? ` data-task-checked="true"` : "";
        return `<li class="${CLS.LIST_ITEM}"${taskAttr}>${item.content}</li>`;
      }).join("");
      out.push({ type: "listgroup", content: `<${tag} class="${cls}">${itemOut}</${tag}>` });
      continue;
    }

    if (b.type === "table") {
      const prev = out[out.length - 1];
      if (prev && prev.type === "table") {
        for (const row of b.rows) prev.rows.push(row);
      } else {
        out.push(b);
      }
      i++;
      continue;
    }

    out.push(b);
    i++;
  }
  return out;
};

/**
 * Renders blocks to HTML string.
 * @param {Block[]} blocks - Array of blocks
 * @returns {string} Rendered HTML
 */
const _render = (blocks) => {
  const out = [];
  for (const b of blocks) {
    if (b.type === "blank") continue;
    const h = _renderBlock(b);
    out.push(h);
    if (h && !["listgroup", "table", "blockquote", "blank", "listitem", "paragraph"].includes(b.type)) {
      out.push("\n");
    }
  }
  return out.join("");
};

/**
 * Normalizes whitespace in markdown text.
 * @param {string} text - Raw markdown text
 * @returns {string} Normalized text
 */
const _normalize = (text) => {
  return text.replace(/[\u2000-\u200B\u202F\u205F\u3000]/g, " ");
};

/**
 * Main entry point: converts markdown to HTML.
 * @param {string} text - Markdown source text
 * @returns {string} Rendered HTML
 */
const makeHtml = (text) => {
  if (typeof text !== "string") return "";
  if (!text || !text.trim() || text.length > 1000000) return "";
  try {
    const normalized = _normalize(text);
    const rawLines = normalized.split(/\r?\n/);
    const lines = rawLines.map(_tab2space);
    const blocks = _parseBlocks(lines, 0, lines.length);
    const grouped = _groupBlocks(blocks);
    const rendered = _render(grouped);
    return HtmlSanitizer.SanitizeHtml(rendered);
  } catch (e) {
    console.warn("Markdown parse error:", e);
    return `<pre class="md-error">${_ent(text)}</pre>`;
  }
};

/**
 * Initializes markdown rendering for all mailbody elements
 * with data-markdown attribute. Called on DOMContentLoaded.
 * @returns {void}
 */
const initMarkdown = () => {
  const mailbodies = document.querySelectorAll(".mailbody[data-markdown]");
  for (const mailbody of mailbodies) {
    if (mailbody.dataset.mdProcessed) continue;
    const content = mailbody.innerText || mailbody.textContent;
    if (content.trim()) {
      const html = makeHtml(content);
      mailbody.innerHTML = html;
      mailbody.dataset.mdProcessed = "true";
    }
  }
};

window.initMarkdown = initMarkdown;
window.makeHtml = makeHtml;

document.addEventListener("DOMContentLoaded", initMarkdown);
