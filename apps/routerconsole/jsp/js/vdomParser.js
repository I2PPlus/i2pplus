/*!
 * vdomParser - HTML to serializable VDOM tree parser for SharedWorkers.
 *
 * Fork of @thednp/domparser (MIT, https://github.com/thednp/domparser) with
 * I2P+ modifications for exact browser-DOM equivalence on console markup:
 *  - text is preserved verbatim (no trimming, whitespace-only nodes kept)
 *  - single-pass entity decoding (text nodes and attributes, not <script>)
 *  - HTML5-lite table rules: implied <tbody> for bare <tr> under <table>,
 *    implicit close of an open cell when a new <td>/<th> starts
 *  - adjacent text nodes are merged ("insert a character" semantics)
 *  - a "/" glued to an unquoted attribute value stays part of that value
 *
 * Loaded via importScripts() in diffWorker.js (classic SharedWorker) and
 * evaluated directly in the Node test harness; attaches self.VdomParser.
 *
 * Original license: MIT (c) thednp, https://github.com/thednp/domparser
 */
"use strict";

/*!
* @thednp/domparser ESM v0.1.9
* Copyright 2026 © thednp
* Licensed under MIT (https://github.com/thednp/domparser/blob/master/LICENSE)
*/

//#region src/parts/util.ts
const ATTR_REGEX = /([^\s=]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\s"']+)))?/g;
/**
* Get attributes from a string token and return an object
* @param token the string token
* @returns the attributes object
*/
const getBaseAttributes = (token) => {
    const attrs = {};
    const [tagName, ...parts] = token.split(/\s+/);
    if (parts.length < 1) return attrs;
    const attrStr = token.slice(tagName.length);
    let match;
    while (match = ATTR_REGEX.exec(attrStr)) {
        const [, name, d, s, u] = match;
        name !== "/" && (attrs[name] = d ?? s ?? u ?? "");
    }
    return attrs;
};
/**
* Get attributes from a string token and return an object.
* In addition to the base tool, this also filters configured
* unsafe attributes.
* @param tagStr the string token
* @param config an optional set of options
* @returns the attributes object
*/
const getAttributes = (tagStr, config) => {
    const { unsafeAttrs } = config || {};
    const baseAttrs = getBaseAttributes(tagStr);
    const attrs = {};
    for (const [key, value] of Object.entries(baseAttrs)) if (!unsafeAttrs || !unsafeAttrs?.has(toLowerCase(key))) attrs[key] = value;
    return attrs;
};
/**
* Converts a string to lowercase.
* @param str The string to convert.
* @returns The lowercase string.
*/
const toLowerCase = (str) => str.toLowerCase();
/**
* Converts a string to uppercase.
* @param str The string to convert.
* @returns The uppercase string.
*/
const toUpperCase = (str) => str.toUpperCase();
/**
* Checks if a string starts with a specified prefix.
* @param str The string to check.
* @param prefix The prefix to search for.
* @param position The position to start looking from.
* @returns `true` if the string starts with the prefix, `false` otherwise.
*/
const startsWith = (str, prefix, position) => str.startsWith(prefix, position);
/**
* Checks if a string ends with a specified suffix.
* @param str The string to check.
* @param suffix The suffix to search for.
* @param position The position to start looking from.
* @returns `true` if the string ends with the suffix, `false` otherwise.
*/
const endsWith = (str, suffix, position) => str.endsWith(suffix, position);
/**
* Creates a string from a character code.
* @param char The character code.
* @returns The string representation of the character code.
*/
const fromCharCode = (char) => String.fromCharCode(char);
/**
* Returns the character code at a specific index in a string.
* @param str The string to check.
* @param index The index of the character to get the code for.
* @returns The character code at the specified index.
*/
const charCodeAt = (str, index) => str.charCodeAt(index);
/**
* Defines a property on an object.
* @param obj The object to define the property on.
* @param propName The name of the property.
* @param desc The property descriptor.
* @returns The object after defining the property.
*/
/**
* Defines multiple properties on an object.
* @param obj The object to define properties on.
* @param props An object where keys are property names and values are property descriptors.
* @returns The object after defining the properties.
*/
const defineProperties = (obj, props) => Object.defineProperties(obj, props);
/**
* Checks if a node is an object.
* @param node The object to check.
* @returns `true` if the node is an object, `false` otherwise.
*/
const isObj = (node) => node !== null && node !== void 0 && typeof node === "object";
/**
* Checks if a node is a root object (`RootNode` or `RootLike`).
* @param node The object to check.
* @returns `true` if the node is an object, `false` otherwise.
*/
const isRoot = (node) => isObj(node) && isNode(node) && node.nodeName === "#document";
/**
* Checks if a node is a tag node (`NodeLike` or `DOMNode`).
* @param node The node to check.
* @returns `true` if the node is a tag node, `false` otherwise.
*/
const isTag = (node) => isObj(node) && "tagName" in node;
/**
* Checks if a node is a root node (`RootLike` or `RootNode`),
* a tag node (`NodeLike` or `DOMNode`), a comment node
* (`CommentLike` or `CommentNode`) or text node (`TextLike` or `TextNode`).
* @param node The node to check.
* @returns `true` if the node is a tag node, `false` otherwise.
*/
const isNode = (node) => isObj(node) && "nodeName" in node;
/**
* Checks if a value is a primitive (number or string).
* @param val The value to check.
* @returns `true` if the value is a primitive, `false` otherwise.
*/
const isPrimitive = (val) => typeof val === "string" || typeof val === "number";
/**
* Trim a string value.
* @param str A string value
* @returns The trimmed value of the same string.
*/
const trim = (str) => str.trim();
/**
* Set of self-closing HTML tags used by the `Parser`.
*/
const selfClosingTags = new Set([
    "?xml",
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
    "path",
    "circle",
    "ellipse",
    "line",
    "rect",
    "use",
    "stop",
    "polygon",
    "polyline"
]);
const escape = (str) => {
    if (str === null || str === "") return "";
    else str = str.toString();
    const map = {
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        "\"": "&quot;",
        "'": "&#039;"
    };
    return str.replace(/[&<>"']/g, (m) => {
        return map[m];
    });
};
const DOM_ERROR = "DomParserError:";
const DEFAULT_CHUNK_SIZE = 64 * 1024;
const DEFAULT_MAX_SCRIPT_SIZE = 128 * 1024;
/**
* Tokenizes an HTML string into an array of HTML tokens.
* These tokens represent opening tags, closing tags, text content, and comments.
* @param html The HTML string to tokenize.
* @returns An array of `HTMLToken` objects.
*/
const tokenize = (html, options = {}) => {
    const { maxScriptSize = DEFAULT_MAX_SCRIPT_SIZE, chunkSize = DEFAULT_CHUNK_SIZE } = options;
    const specialTags = ["script", "style"];
    const tokens = [];
    const len = html.length;
    const COM_START = ["!--", "![CDATA["];
    const COM_END = ["--", "]]"];
    let COM_TYPE = 0;
    let token = "";
    let scriptContent = "";
    let inTag = false;
    let inQuote = false;
    let quote = 0;
    let inPre = false;
    let inTemplate = false;
    let inComment = false;
    let inStyleScript = false;
    let currentChunkStart = 0;
    while (currentChunkStart < len) {
        const chunkEnd = Math.min(currentChunkStart + chunkSize, len);
        const chunk = html.slice(currentChunkStart, chunkEnd);
        for (let i = 0; i < chunk.length; i++) {
            const globalIndex = currentChunkStart + i;
            const char = charCodeAt(chunk, i);
            if (inStyleScript) {
                const endSpecialTag = specialTags.find((t) => startsWith(html, `/${t}`, globalIndex + 1));
                if (char === 60 && endSpecialTag && !inTemplate && !inQuote) {
                    if (scriptContent && scriptContent.length < maxScriptSize) tokens.push({
                        tokenType: "text",
                        value: scriptContent,
                        isSC: false
                    });
                    tokens.push({
                        tokenType: "tag",
                        value: "/" + endSpecialTag,
                        isSC: false
                    });
                    scriptContent = "";
                    inStyleScript = false;
                    i += endSpecialTag.length + 2;
                } else {
                    if (scriptContent.length >= maxScriptSize) continue;
                    if (char === 96) inTemplate = !inTemplate;
                    else if (!inTemplate && (char === 34 || char === 39)) {
                        if (!inQuote) {
                            quote = char;
                            inQuote = true;
                        } else if (char === quote) inQuote = false;
                    }
                    scriptContent += fromCharCode(char);
                }
                continue;
            }
            if (inComment) {
                token += fromCharCode(char);
                if (endsWith(token, COM_END[COM_TYPE]) && charCodeAt(html, globalIndex + 1) === 62) {
                    // Browser-compatible comment value: the inner content only,
                    // delimiters excluded, kept verbatim (no trimming).
                    const inner = token.slice(COM_START[COM_TYPE].length, token.length - COM_END[COM_TYPE].length);
                    const tokenValue = COM_TYPE === 1 ? escape(inner) : inner;
                    tokens.push({
                        tokenType: "comment",
                        value: tokenValue,
                        isSC: false
                    });
                    inComment = false;
                    token = "";
                    i += 1;
                }
                continue;
            }
            if (inTag && token.includes("=") && (char === 34 || char === 39)) {
                if (!inQuote) {
                    quote = char;
                    inQuote = true;
                } else if (char === quote) inQuote = false;
                token += fromCharCode(char);
                continue;
            }
            if (char === 60 && !inQuote && !inTemplate) {
                if (token) tokens.push({
                    tokenType: "text",
                    value: token,
                    isSC: false
                });
                token = "";
                const commentStart = COM_START.find((cs) => startsWith(html, cs, globalIndex + 1));
                if (commentStart) {
                    COM_TYPE = COM_START.indexOf(commentStart);
                    inComment = true;
                    token += commentStart;
                    i += commentStart.length;
                    continue;
                }
                inTag = true;
            } else if (char === 62 && inTag && !inTemplate) {
                if (token === "/pre") inPre = false;
                else if (token === "pre" || startsWith(token, "pre")) inPre = true;
                if (specialTags.find((t) => t === token || startsWith(token, t)) && !endsWith(token, "/")) inStyleScript = true;
                const isDocType = startsWith(toLowerCase(token), "!doctype");
                if (token) {
                    let isSC = endsWith(token, "/");
                    if (isSC) {
                        // HTML5: a "/" glued to an unquoted attribute value is part of that value
                        const before = token.slice(0, -1);
                        const lastWs = Math.max(before.lastIndexOf(" "), before.lastIndexOf("\t"), before.lastIndexOf("\n"));
                        const lastEq = before.lastIndexOf("=");
                        if (lastEq > lastWs && !/["'\s]$/.test(before)) isSC = false;
                    }
                    const [tagName] = token.split(/\s/);
                    const value = inQuote ? tagName + (isSC ? "/" : "") : token;
                    tokens.push({
                        tokenType: isDocType ? "doctype" : "tag",
                        value: isSC ? trim(value.slice(0, -1)) : trim(value),
                        isSC
                    });
                }
                token = "";
                inTag = false;
                inQuote = false;
            } else token += fromCharCode(char);
        }
        currentChunkStart = chunkEnd;
    }
    if (token) tokens.push({
        tokenType: "text",
        value: token,
        isSC: false
    });
    return tokens;
};
//#endregion


/*!
* @thednp/domparser ESM v0.1.9
* Copyright 2026 © thednp
* Licensed under MIT (https://github.com/thednp/domparser/blob/master/LICENSE)
*/


//#region src/parts/parser.ts
/**
* **Parser**
*
* A tiny yet very fast and powerful parser that takes a string of HTML
* and returns a DOM tree representation. In benchmarks it shows up to
* 60x faster performance when compared to jsdom.
*
* @example
* ```ts
* const { root, components, tags } = Parser().parseFromString("<h1>Title</h1>");
* // > "root" is a RootLike node,
* // > "components" is an array of component names,
* // > "tags" is an array of tag names.
* ```
*
* @returns The result of the parser.
*/
function Parser() {
    return { parseFromString(htmlString) {
        const root = {
            nodeName: "#document",
            children: []
        };
        if (!htmlString) return {
            root,
            tags: [],
            components: []
        };
        const stack = [root];
        const components = /* @__PURE__ */ new Set();
        const tags = /* @__PURE__ */ new Set();
        const tokens = tokenize(htmlString);
        const tLen = tokens.length;
        for (let i = 0; i < tLen; i += 1) {
            const { tokenType, value, isSC } = tokens[i];
            const currentParent = stack[stack.length - 1];
            if (tokenType === "doctype") continue;
            if (["text", "comment"].includes(tokenType)) {
                const parentKids = currentParent.children;
                if (tokenType === "text" && parentKids.length && parentKids[parentKids.length - 1].nodeName === "#text") {
                    // HTML5 "insert a character": merge adjacent text into the last text node
                    parentKids[parentKids.length - 1].nodeValue += value;
                } else {
                    parentKids.push({
                        nodeName: `#${tokenType}`,
                        nodeValue: value
                    });
                }
                continue;
            }
            const isClosing = value.startsWith("/");
            const tagName = isClosing ? value.slice(1) : value.split(/[\s/>]/)[0];
            const tagNameLower = toLowerCase(tagName);
            const isSelfClosing = isSC || selfClosingTags.has(tagNameLower);
            (tagName[0] === toUpperCase(tagName[0]) || tagName.includes("-") ? components : tags).add(tagName);
            if (!isClosing) {
                const node = {
                    tagName,
                    nodeName: toUpperCase(tagName),
                    attributes: getBaseAttributes(value),
                    children: []
                };
                let parent = currentParent;
                if (tagNameLower === "tr" && parent.tagName === "table") {
                    // implied <tbody> when a <tr> is a direct child of <table>
                    const implied = {
                        tagName: "tbody",
                        nodeName: "TBODY",
                        attributes: {},
                        children: []
                    };
                    parent.children.push(implied);
                    stack.push(implied);
                    parent = implied;
                } else if ((tagNameLower === "td" || tagNameLower === "th") && parent.tagName !== "tr") {
                    // a new cell implicitly closes any open cell (clear back to the row)
                    while (stack.length > 1 && stack[stack.length - 1].tagName !== "tr") stack.pop();
                    parent = stack[stack.length - 1];
                }
                parent.children.push(node);
                !isSelfClosing && stack.push(node);
            } else if (!isSelfClosing && stack.length > 1) stack.pop();
        }
        return {
            root,
            components: Array.from(components),
            tags: Array.from(tags)
        };
    } };
}
//#endregion


/* --------------------- I2P+ additions: decode + entry point --------------- */

/** Single-pass entity decode; fast path returns untouched when no "&". */
const NAMED_ENTITIES = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'",
  nbsp: "\u00a0", ensp: "\u2002", emsp: "\u2003", thinsp: "\u2009", zwnj: "\u200c", zwj: "\u200d",
  hellip: "\u2026", middot: "\u00b7", bull: "\u2022", bullet: "\u2022", minus: "\u2212",
  ndash: "\u2013", mdash: "\u2014", lsquo: "\u2018", rsquo: "\u2019", ldquo: "\u201c", rdquo: "\u201d",
  laquo: "\u00ab", raquo: "\u00bb", copy: "\u00a9", reg: "\u00ae", trade: "\u2122",
  times: "\u00d7", divide: "\u00f7", euro: "\u20ac", pound: "\u00a3", yen: "\u00a5", cent: "\u00a2",
  szlig: "\u00df", aacute: "\u00e1", eacute: "\u00e9", iacute: "\u00ed", oacute: "\u00f3", uacute: "\u00fa",
  agrave: "\u00e0", egrave: "\u00e8", igrave: "\u00ec", ograve: "\u00f2", ugrave: "\u00f9",
  ntilde: "\u00f1", aring: "\u00e5", ccedil: "\u00e7", deg: "\u00b0", plusmn: "\u00b1"
};
const ENTITY_RE = /&(?:[a-zA-Z][a-zA-Z0-9]+|#\d+|#[xX][0-9a-fA-F]+);/g;

function decodeEntities(value) {
  if (value.indexOf("&") < 0) { return value; }
  return value.replace(ENTITY_RE, function(m) {
    const body = m.slice(1, -1);
    if (body.charCodeAt(0) === 35) {
      const code = body.charCodeAt(1) === 120 || body.charCodeAt(1) === 88
        ? parseInt(body.slice(2), 16)
        : parseInt(body.slice(1), 10);
      if (isNaN(code) || code > 0x10FFFF) { return m; }
      try { return String.fromCodePoint(code); } catch (e) { return m; }
    }
    return NAMED_ENTITIES[body] !== undefined ? NAMED_ENTITIES[body] : m;
  });
}

/**
 * Decodes entities in text nodes and attribute values. Text inside <script>
 * and <style> is raw by definition and is left untouched.
 * @function decodeTree
 * @param {Object} node - The VDOM node to walk
 * @param {boolean} inRawText - True inside a <script>/<style> element
 * @returns {void}
 */
function decodeTree(node, inRawText) {
  const kids = node.children;
  if (!Array.isArray(kids)) { return; }
  for (let i = 0; i < kids.length; i++) {
    const child = kids[i];
    const nn = child.nodeName;
    if (nn === "#text") {
      if (!inRawText) { child.nodeValue = decodeEntities(child.nodeValue); }
    } else {
      const tag = child.tagName;
      if (tag === "script" || tag === "style") {
        decodeTree(child, true);
      } else {
        const attrs = child.attributes;
        if (attrs) {
          for (const k in attrs) {
            const v = attrs[k];
            if (v.indexOf("&") >= 0) { attrs[k] = decodeEntities(v); }
          }
        }
        decodeTree(child, false);
      }
    }
  }
}

/**
 * Parses an HTML string into a serializable VDOM tree whose shape matches
 * the browser DOM for console markup (see file header for the deviations
 * covered). The tree is plain data: {tagName, attributes, children} for
 * elements, {nodeName:"#text"|"#comment", nodeValue} for text and comments.
 * @function parse
 * @param {string} htmlString - The HTML to parse
 * @returns {Object} The root node (nodeName "#document", children)
 */
function parse(htmlString) {
  const root = Parser().parseFromString(htmlString).root;
  decodeTree(root, false);
  return root;
}

const VdomParser = { parse };
if (typeof self !== "undefined") { self.VdomParser = VdomParser; }
