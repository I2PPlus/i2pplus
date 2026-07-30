/**
 * @module sortShared
 * @description Pure comparison functions shared between tablesort.js (main thread)
 * and sortWorker.js (web worker). All comparators return ascending order;
 * empty/null cells always sort after non-empty values. The caller inverts direction.
 * Based on tristen/tablesort (MIT) https://github.com/tristen/tablesort,
 * ported to standalone functions by dr|z3d for I2P+ (AGPLv3).
 * @license AGPLv3 or later
 */

/** @returns {boolean} True if null, undefined, or whitespace-only. */
function empty(v) { return v == null || String(v).trim() === ""; }

/**
 * Wrap a comparator so empty cells always sort to the bottom in ascending order.
 * @param {function(string, string): number} cmp - Ascending comparator
 * @returns {function(string, string): number}
 */
function emptyLast(cmp) {
  return function(a, b) {
    const ae = empty(a), be = empty(b);
    if (ae || be) return ae && be ? 0 : ae ? 1 : -1;
    return cmp(a, b);
  };
}

/**
 * Number comparator — strips non-numeric chars except E/e/%.
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
function numberCmp(a, b) {
  const clean = v => typeof v === "string" ? v.replace(/[^-0-9.Ee%]/g, "") : "";
  const na = parseFloat(clean(a)), nb = parseFloat(clean(b));
  if (isNaN(na) && isNaN(nb)) return 0;
  if (isNaN(na)) return 1;
  if (isNaN(nb)) return -1;
  return na - nb;
}

/** @param {string} item */
function numberPattern(item) {
  const cleaned = typeof item === "string" ? item.replace(/[^-0-9.Ee%]/g, "") : "";
  if (cleaned === "") return false;
  const n = parseFloat(cleaned);
  return !isNaN(n) && isFinite(n);
}

/**
 * Natural sort — splits text into numeric/text chunks for human-friendly ordering.
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
function naturalCmp(a, b) {
  if (a === b) return 0;
  if (a === "unknown") return 1;
  if (b === "unknown") return -1;
  const aParts = a.toLowerCase().match(/(\.\d+)|(\d+(\.\d+)?)|([^\d.]+)/g) || [];
  const bParts = b.toLowerCase().match(/(\.\d+)|(\d+(\.\d+)?)|([^\d.]+)/g) || [];
  const min = Math.min(aParts.length, bParts.length);
  for (let i = 0; i < min; i++) {
    const aNum = parseFloat(aParts[i]), bNum = parseFloat(bParts[i]);
    if (!isNaN(aNum) && !isNaN(bNum)) {
      if (aNum !== bNum) return aNum - bNum;
    } else if (aParts[i] !== bParts[i]) {
      return aParts[i] < bParts[i] ? -1 : 1;
    }
  }
  return aParts.length - bParts.length;
}

/** @param {string} item */
function naturalPattern(item) { return item != null && item.trim() !== ""; }

/**
 * Dot-separated comparator (IPs, version numbers).
 * Pads shorter segment lists with zeros for unequal-length comparison.
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
function dotsepCmp(a, b) {
  const aParts = a.split("."), bParts = b.split(".");
  const len = Math.max(aParts.length, bParts.length);
  for (let i = 0; i < len; i++) {
    const ai = parseInt(aParts[i] || "0", 10), bi = parseInt(bParts[i] || "0", 10);
    if (ai !== bi) return ai - bi;
  }
  return 0;
}

/** @param {string} item */
function dotsepPattern(item) { return /^(\d+\.)+\d+$/.test(item); }

/**
 * Filesize comparator — converts with/without suffix to bytes (1024-base).
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
function filesizeCmp(a, b) {
  const toBytes = f => {
    const m = f.match(/^(\d+(\.\d+)?) ?((K|M|G|T|P|E|Z|Y|B$)i?B?)$/i);
    if (!m) return 0;
    const num = parseFloat(m[1].replace(/[^\-?0-9.]/g, ""));
    const suf = (m[3][0] || "").toLowerCase();
    const mult = suf === "k" ? 1024 : suf === "m" ? 1048576 : suf === "g" ? 1073741824 :
                 suf === "t" ? 1099511627776 : suf === "p" ? 1125899906842624 :
                 suf === "e" ? 1152921504606846976 : suf === "z" ? 1180591620717411303424 :
                 suf === "y" ? 1208925819614629174706176 : 1;
    return num * mult;
  };
  return toBytes(a) - toBytes(b);
}

/** @param {string} item */
function filesizePattern(item) {
  return /^\d+(\.\d+)? ?(K|M|G|T|P|E|Z|Y|B$)i?B?$/i.test(item);
}

/** Month-name comparator. @param {string} a @param {string} b @returns {number} */
function monthnameCmp(a, b) {
  const names = ["January", "February", "March", "April", "May", "June",
                 "July", "August", "September", "October", "November", "December"];
  return names.indexOf(a) - names.indexOf(b);
}

/** @param {string} item */
function monthnamePattern(item) {
  return /January|February|March|April|May|June|July|August|September|October|November|December/i.test(item);
}

/**
 * Date comparator — normalises separators and formats before parsing.
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
function dateCmp(a, b) {
  const parseDate = d => {
    d = d.replace(/-/g, "/").replace(/(\d{1,2})\/(\d{1,2})\/(\d{2,4})/, "$3-$2-$1");
    const t = new Date(d).getTime();
    return isNaN(t) ? -1 : t;
  };
  return parseDate(a.toLowerCase()) - parseDate(b.toLowerCase());
}

/** @param {string} item */
function datePattern(item) {
  const parseDate = d => {
    d = d.replace(/-/g, "/").replace(/(\d{1,2})\/(\d{1,2})\/(\d{2,4})/, "$3-$2-$1");
    return isNaN(new Date(d).getTime()) ? -1 : new Date(d).getTime();
  };
  if (parseDate(item) === -1) return false;
  return /(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\.?\,?\s*/i.test(item) ||
         /\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}/.test(item) ||
         /(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)/i.test(item);
}

/** Intl.Collator comparator (locale-aware). @param {string} a @param {string} b @returns {number} */
function intlCmp(a, b) { return new Intl.Collator().compare(a, b); }

/** Intl never auto-detects; requires data-sort-method="intl". @returns {boolean} */
function intlPattern() { return false; }

/** Default string fallback (case-insensitive). @param {string} a @param {string} b @returns {number} */
function stringCmp(a, b) {
  a = String(a).toLowerCase();
  b = String(b).toLowerCase();
  return a < b ? -1 : (a > b ? 1 : 0);
}

// Exported comparators (empty-last wrapped)
const numberCmpEL = emptyLast(numberCmp);
const naturalCmpEL = emptyLast(naturalCmp);
const dotsepCmpEL = emptyLast(dotsepCmp);
const filesizeCmpEL = emptyLast(filesizeCmp);
const monthnameCmpEL = emptyLast(monthnameCmp);
const dateCmpEL = emptyLast(dateCmp);
const intlCmpEL = emptyLast(intlCmp);
const stringCmpEL = emptyLast(stringCmp);
