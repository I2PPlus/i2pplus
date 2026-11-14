/**
 * Tree-wide audit of how every console webapp loads its client scripts.
 *
 * A <script> tag and a resource hint have to agree with the file they name and
 * with each other, and nothing in the build enforces that: a file gains a
 * top-level import, or a page adds `type=module` to defer a classic script, and
 * the mismatch only shows up as a runtime error or a second fetch of the same
 * URL. This test reads the emitters (JSP, JSI, HTML and the Java that writes
 * script tags out) and the scripts themselves, and holds them to six rules:
 *
 *   1. a file with top-level import or export is never loaded as a classic
 *      script, nor preloaded with `as=script` - a classic load of it is a
 *      SyntaxError ("import declarations may only appear at top level of a
 *      module") that only fires on that one page;
 *   2. a file without them is never `modulepreload`ed unless the same page also
 *      loads it with type=module: modulepreload fills the module map, and a
 *      classic <script src> of the same URL is a separate fetch, so the hint
 *      buys a second copy of the file instead of an earlier one;
 *   3. one page never loads the same file both ways, nor one with a `?version=`
 *      query and one without - that is two URLs, two cache entries, and on one
 *      page two executions of the same code;
 *   4. every `rel=preload as=script` hint is matched by a classic load of that
 *      file on the same page, or it is a fetch nothing consumes;
 *   5. two scripts one page loads never declare the same top-level name, because
 *      on a page they share one window and the second declaration silently
 *      overwrites the first - which copy runs is then an accident of tag order;
 *   6. a classic script exports no name on the window that nothing outside its
 *      own file mentions: such a name is not an API, it is pollution, and it is
 *      the kind of collision rule 5 exists to catch.
 *
 * Rules 5 and 6 only bite for scripts that share a document: a worker runs in a
 * global of its own and is excluded, and a module has a scope of its own.
 *
 * The extractors are probed with fixtures first: an audit whose regexes stop
 * matching reports zero violations, which looks exactly like a clean tree.
 *
 * Vendored trees are skipped (tools/, node_modules/, build output) - their
 * loaders are their own, and a bundler's modulepreload of a hashed chunk is
 * none of our business.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
// pure -> js -> test -> tools -> repo root
const repoRoot = join(here, "..", "..", "..", "..");

/** Where the webapps live; everything else in the tree is tooling. */
const APPS = join(repoRoot, "apps");

/** Directories that hold build output or someone else's bundled web UI. */
const SKIP_DIRS = new Set(["build", "dist", "node_modules", "target", "sonarqube", "codeql"]);

/** Files that can emit a script tag or a resource hint. */
const EMITTERS = [".jsp", ".jsi", ".html", ".java"];

/**
 * A file is a module only when it carries a top-level import or export. Lines
 * starting with "*" are JSDoc and cannot produce one, and an `import` inside a
 * string or a comment is not at the start of a line.
 *
 * @param {string} source the file's contents
 * @returns {boolean} true when the file needs module loading semantics
 */
function isModule(source) {
  return /^(?:import|export)[\s{*]/m.test(source);
}

/**
 * Keeps only what the string literals of a Java emitter say, so a tag split
 * across `.append()` calls and interpolated paths becomes one run of HTML. The
 * interpolated expressions (a resource path, a version) fall away, which is what
 * we want: the path tail we match on is always inside a literal. Comments and
 * char literals are skipped so a `<script` in a comment cannot be read as a tag.
 *
 * @param {string} text the emitter's source
 * @param {string} file the emitter's path, to pick the treatment
 * @returns {string} the text to scan for tags
 */
function unwrap(text, file) {
  // a JSP expression is not HTML: `<%= version %>` inside a src attribute would
  // otherwise end the tag at the `%>` and hide the attributes that follow it
  if (file.endsWith(".jsp") || file.endsWith(".jsi")) {return text.replace(/<%[\s\S]*?%>/g, " ");}

  if (!file.endsWith(".java")) {return text;}

  let out = "";
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === "/" && text[i + 1] === "/") {
      i = text.indexOf("\n", i);
      if (i === -1) {break;}
    } else if (c === "/" && text[i + 1] === "*") {
      i = text.indexOf("*/", i + 2);
      if (i === -1) {break;}
      i += 2;
    } else if (c === '"') {
      i++;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === "\\") {
          // an escape is a boundary in the emitted HTML, never part of a name:
          // a literal ending in \n must not weld its \n onto the next literal
          out += " ";
          i += 2;
          continue;
        }
        out += text[i];
        i++;
      }
      i++;
    } else if (c === "'") {
      i++;
      while (i < text.length && text[i] !== "'") {
        if (text[i] === "\\") {i++;}
        i++;
      }
      i++;
    } else {
      i++;
    }
  }
  return out;
}

/**
 * Every load of a .js file that an emitter asks for, found by working outward
 * from the path to the tag that carries it. The path-first direction is what
 * makes the Java idiom scannable: a servlet that builds `"<script src=" + path`
 * once and then writes `js/x.js type=module></script>` per script has no tag
 * opening next to most of its script names.
 *
 * The query string is kept, because `x.js` and `x.js?version=0.9.71` are two
 * URLs: two cache entries, two parses, and on one page two executions.
 *
 * @param {string} text the (possibly unwrapped) emitter source
 * @returns {Array<{kind: string, url: string, query: string}>} one entry per
 *   load, kind is "module", "classic", "modulepreload" or "preload-script", and
 *   query is the `?...` written after the path, empty when there is none
 */
function loadsIn(text) {
  const found = [];
  for (const match of text.matchAll(/[\w./-]+\.js\b(\?[^\s"'<>]*)?/g)) {
    const open = Math.max(text.lastIndexOf("<script", match.index), text.lastIndexOf("<link", match.index));
    const close = text.indexOf(">", match.index);
    if (open < 0 || close < 0 || close - open > 400) {continue;}

    const tag = text.slice(open, close + 1);
    let kind;
    if (tag.startsWith("<link")) {
      if (/\brel=("?)modulepreload\1/i.test(tag)) {kind = "modulepreload";}
      else if (/\brel=("?)preload\1/i.test(tag) && /\bas=("?)script\1/i.test(tag)) {kind = "preload-script";}
      else {continue;}
    } else {
      kind = /\btype=("?)module\1/i.test(tag) ? "module" : "classic";
    }
    found.push({kind, url: match[0].split("?")[0], query: match[1] === undefined ? "" : match[1]});
  }
  return found;
}

/**
 * Lists a directory tree, skipping build output and vendored tooling.
 *
 * @param {string} dir the directory to walk
 * @returns {string[]} every file below it that is not skipped
 */
function walk(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    const stats = statSync(path);
    if (stats.isDirectory()) {
      if (!SKIP_DIRS.has(entry)) {files.push(...walk(path));}
    } else if (!entry.endsWith(".min.js")) {
      files.push(path);
    }
  }
  return files;
}

const allFiles = walk(APPS);

/** Every client script in the webapps, indexed by file name. */
const scripts = new Map();
for (const file of allFiles.filter(f => f.endsWith(".js"))) {
  const name = file.split(/[\\/]/).pop();
  if (!scripts.has(name)) {scripts.set(name, []);}
  scripts.get(name).push(file);
}

/**
 * Finds the script a URL names. The URL of a webapp asset does not mirror its
 * path in the tree (`/susidns/js/x.js` is served from `apps/susidns/src/js/`),
 * so the URL's segments are matched against the file path back to front as a
 * subsequence, and a file that lives in the same webapp as the page asking for
 * it wins a tie. A tie that no rule breaks is left unresolved rather than
 * guessed at.
 *
 * @param {string} url the src or href as written in the emitter
 * @param {string} emitter the page or servlet that writes it
 * @returns {string|undefined} the file that serves it, if the tree holds one
 */
function resolve(url, emitter) {
  const segments = url.split("/").filter(Boolean);
  const candidates = scripts.get(segments[segments.length - 1]);
  if (candidates === undefined) {return undefined;}

  const app = emitter.slice(APPS.length + 1).split(/[\\/]/)[0];
  let best;
  let bestScore = 0;
  for (const candidate of candidates) {
    const parts = candidate.slice(APPS.length + 1).split(/[\\/]/);
    let score = 0;
    let at = parts.length - 1;
    for (let want = segments.length - 1; want >= 0; want--) {
      while (at >= 0 && parts[at] !== segments[want]) {at--;}
      if (at < 0) {score = -1; break;}
      score++;
      at--;
    }
    if (score < 0) {continue;}
    score = score * 10 + (parts[0] === app ? 1 : 0);
    if (score > bestScore) {best = candidate; bestScore = score;}
    else if (score === bestScore) {best = undefined;}
  }
  return best;
}

/**
 * What each page asks of each script: the load kinds it uses, and the query
 * forms it uses. A bare path and a path with a `?version=` are two URLs, so a
 * page that writes both holds two copies of the same file in memory and runs it
 * twice.
 */
const seen = new Map();
let resolvedTags = 0;

for (const file of allFiles.filter(f => EMITTERS.includes(f.slice(f.lastIndexOf("."))))) {
  const text = unwrap(readFileSync(file, "utf8"), file);
  for (const load of loadsIn(text)) {
    const target = resolve(load.url, file);
    if (target === undefined) {continue;}
    resolvedTags++;
    const key = `${file}\u0000${target}`;
    if (!seen.has(key)) {seen.set(key, {kinds: new Set(), queries: new Set()});}
    const entry = seen.get(key);
    entry.kinds.add(load.kind);
    entry.queries.add(load.query === "" ? "plain" : "versioned");
  }
}

/** True for the kinds that ask for module semantics. */
const isModuleKind = kind => kind === "module" || kind === "modulepreload";

test("the audit sees the tree it claims to", () => {
  // a walk or a regex that stops matching reads as a clean tree, so pin the
  // scale of what is actually being checked
  assert.ok(resolvedTags > 120, `only ${resolvedTags} script loads resolved under apps/`);
  assert.ok(seen.size > 60, `only ${seen.size} page/script pairs found`);
  assert.ok(scripts.has("iframeResizer.contentWindow.js"),
    "and the index knows the iframe sizing pair");
});

test("the extractors still find what they are looking for", () => {
  const html = unwrap(`
    <script src=/js/a.js></script>
    <script src="/js/b.js" type=module defer></script>
    <link rel=modulepreload href=/js/c.js>
    <link rel=preload as=script href=/js/d.js>
    <link rel=preload as=style href=/themes/e.css>
    <script nonce=x>const theme = "dark";</script>
  `, "page.jsp");
  assert.deepEqual(loadsIn(html).map(t => [t.kind, t.url]),
    [["classic", "/js/a.js"], ["module", "/js/b.js"], ["modulepreload", "/js/c.js"],
     ["preload-script", "/js/d.js"]],
    "each load kind is told apart, and a stylesheet or an inline script is not");

  const java = unwrap(`
      String jsPath = "<script src=" + resourcePath + "js/";
      out.write(jsPath + "e.js type=module></script>\\n");
      buf.append("<link rel=modulepreload href=").append(resourcePath).append("js/f.js>\\n");
      buf.append("<script src=").append(_resourcePath).append("js/g.js></script>\\n");
  `, "Servlet.java");
  assert.deepEqual(loadsIn(java).map(t => [t.kind, t.url]),
    [["module", "js/e.js"], ["modulepreload", "js/f.js"], ["classic", "js/g.js"]],
    "a tag whose opening sits in a shared variable, one split across .append() calls, " +
    "and one written out whole are all found");

  const jsp = unwrap('<script src="js/h.js?<%=net.i2p.CoreVersion.VERSION%>" type=module></script>', "page.jsp");
  assert.deepEqual(loadsIn(jsp).map(t => [t.kind, t.url]), [["module", "js/h.js"]],
    "a JSP expression inside a src does not end the tag at its percent-greater-than");

  const escaped = unwrap('out.write("<script src=js/i.js></script>\\n");\n' +
    'out.write("js/j.js type=module></script>\\n");', "Servlet.java");
  assert.deepEqual(loadsIn(escaped).map(t => [t.kind, t.url]),
    [["classic", "js/i.js"], ["module", "js/j.js"]],
    "a literal ending in an escape does not weld that escape onto the next file name");

  const dup = unwrap('<script src=/js/k.js></script>\n<script src="/js/k.js?version=<%=v%>"></script>', "page.jsp");
  assert.deepEqual(loadsIn(dup).map(t => [t.url, t.query]),
    [["/js/k.js", ""], ["/js/k.js", "?version="]],
    "the query is kept, so one page naming the same file both ways is caught");

  // two webapps ship a togglePanels.js, and the URL of an asset does not mirror
  // its path in the tree, so this is where a resolver quietly picks the wrong one
  const susidns = resolve("susidns/js/togglePanels.js", join(APPS, "susidns", "src", "jsp", "a.jsp"));
  const snark = resolve("js/togglePanels.js", join(APPS, "i2psnark", "java", "src", "S.java"));
  assert.ok(susidns !== undefined && susidns.includes("susidns"),
    "an absolute webapp path resolves inside that webapp");
  assert.ok(snark !== undefined && snark.includes("i2psnark"),
    "a relative path resolves to the webapp that writes it");

  assert.equal(isModule('import {a} from "b";\nconst c = 1;'), true, "a top-level import");
  assert.equal(isModule("export const a = 1;"), true, "a top-level export");
  assert.equal(isModule('  const s = "import x from";\n'), false, "an import inside a string");
  assert.equal(isModule("/**\n * Derived from foo; export the results.\n */\nfunction f() {}"),
    false, "the word in a comment");
  assert.equal(isModule("(function () {\n  return 1;\n})();\n"), false, "an IIFE");
});

test("no page loads a script both as a module and as a classic script", () => {
  const violations = [];
  for (const [key, {kinds}] of seen) {
    const [page, target] = key.split("\u0000");
    const moduleish = [...kinds].filter(isModuleKind);
    const classicish = [...kinds].filter(k => !isModuleKind(k));
    if (moduleish.length && classicish.length) {
      violations.push(`${page} loads ${target} as ${[...kinds].sort().join(" + ")}`);
    }
  }
  assert.deepEqual(violations, []);
});

test("a module is never loaded as a classic script", () => {
  const violations = [];
  for (const [key, {kinds}] of seen) {
    const [page, target] = key.split("\u0000");
    if (!isModule(readFileSync(target, "utf8"))) {continue;}
    const classicish = [...kinds].filter(k => !isModuleKind(k));
    if (classicish.length) {
      violations.push(`${page} loads the module ${target} as ${classicish.sort().join(" + ")}`);
    }
  }
  assert.deepEqual(violations, []);
});

test("a classic script is never preloaded as a module", () => {
  const violations = [];
  for (const [key, {kinds}] of seen) {
    const [page, target] = key.split("\u0000");
    if (!kinds.has("modulepreload") || isModule(readFileSync(target, "utf8"))) {continue;}
    // modulepreload fills the module map; only a type=module load of the same
    // file on the same page can use what it fetched
    if (!kinds.has("module")) {
      violations.push(`${page} modulepreloads the classic script ${target} but never loads it as a module`);
    }
  }
  assert.deepEqual(violations, []);
});

test("every preload hint is matched by the load it prepares", () => {
  const violations = [];
  for (const [key, {kinds}] of seen) {
    const [page, target] = key.split("\u0000");
    if (!kinds.has("preload-script")) {continue;}
    if (!kinds.has("classic") && !kinds.has("module")) {
      violations.push(`${page} preloads ${target} as=script but never loads it`);
    }
  }
  assert.deepEqual(violations, []);
});

test("no page loads one script both with and without a query", () => {
  // `x.js` and `x.js?version=0.9.71` are two URLs: the browser keeps a copy of
  // each and runs each, so one page writing both pays for the file twice
  const violations = [];
  for (const [key, {queries}] of seen) {
    const [page, target] = key.split("\u0000");
    if (queries.size > 1) {
      violations.push(`${page} loads ${target} ${[...queries].sort().join(" and ")}`);
    }
  }
  assert.deepEqual(violations, []);
});

/**
 * Groups what every page in the tree asks of one script.
 *
 * @param {"queries" | "kinds"} field the aspect to collect
 * @returns {Map<string, Set<string>>} the script file to every form any page uses
 */
function formsAcrossTree(field) {
  const out = new Map();
  for (const [key, entry] of seen) {
    const [, target] = key.split("\u0000");
    if (!out.has(target)) {out.set(target, new Set());}
    for (const value of entry[field]) {out.get(target).add(value);}
  }
  return out;
}

test("one script is not versioned on some pages and plain on others", () => {
  // across pages this is not a double execution but a double cache entry: the
  // webapps serve static files immutable for a month, so both copies stay hot
  const mixed = [];
  for (const [target, forms] of formsAcrossTree("queries")) {
    if (forms.size > 1) {mixed.push(`${target} loaded ${[...forms].sort().join(" and ")}`);}
  }
  assert.deepEqual(mixed, []);
});

test("one script is not a module on some pages and a classic script on others", () => {
  // two load kinds for one file is two parses and two records of it, and the two
  // run at different points of the parse: a classic script during it, a module
  // after it, which is exactly the ordering a page's other scripts can depend on
  const mixed = [];
  for (const [target, kinds] of formsAcrossTree("kinds")) {
    const all = [...kinds];
    if (all.some(isModuleKind) && all.some(kind => !isModuleKind(kind))) {
      mixed.push(`${target} loaded as ${all.sort().join(" + ")}`);
    }
  }
  assert.deepEqual(mixed, []);
});

/**
 * A worker is loaded by `importScripts` or owns the port directly, so it runs in
 * its own global and shares no window with the page. Its top-level names are not
 * leaks and it is not a page script.
 *
 * @param {string} source the file's contents
 * @returns {boolean} true when the file runs in a worker scope
 */
function isWorker(source, path) {
  return /\bimportScripts\s*\(/.test(source) ||
    /^\s*(?:self\.)?on(?:message|messageerror)\s*=/m.test(source) ||
    /Worker\.js$/.test(path);
}

/**
 * The names a classic page script puts on the shared window. A top-level
 * `function`/`var`/`let`/`const` at column 0 is one, and so is an assignment to
 * `window.x` or a bare `x =` at any indent - the second form is how a script
 * inside an IIFE exports a name on purpose, and it still lands on the window.
 *
 * @param {string} source the file's contents
 * @returns {string[]} the names it publishes, in source order
 */
function leakedNames(source) {
  const names = new Set();
  for (const m of source.matchAll(/^(?:function|var|let|const)\s+([A-Za-z_$][\w$]*)/gm)) {names.add(m[1]);}
  for (const m of source.matchAll(/^window\.([A-Za-z_$][\w$]*)\s*=/gm)) {names.add(m[1]);}
  for (const m of source.matchAll(/^globalThis\.([A-Za-z_$][\w$]*)\s*=/gm)) {names.add(m[1]);}
  // a bare assignment is an implicit global only at the top level, i.e. column 0:
  // the same statement indented inside a function writes a local
  for (const m of source.matchAll(/^([A-Za-z_$][\w$]*)\s*=\s/gm)) {names.add(m[1]);}
  return [...names];
}

/** Every classic page script, with the names it puts on the window. */
const leaks = new Map();
for (const file of [...scripts.values()].flat()) {
  const source = readFileSync(file, "utf8");
  if (isModule(source) || isWorker(source, file)) {continue;}
  const names = leakedNames(source);
  if (names.length) {leaks.set(file, names);}
}

/** Every script, by path, that at least one emitter loads. */
const loadedScripts = new Set([...seen.keys()].map(key => key.split("\u0000")[1]));

/** The classic page scripts the audit reads, whether or not they leak anything. */
const classicScripts = new Set();
for (const file of [...scripts.values()].flat()) {
  const source = readFileSync(file, "utf8");
  if (!isModule(source) && !isWorker(source, file)) {classicScripts.add(file);}
}

/** Every file the audit reads as text, for the "does anything else say this" test. */
const corpus = new Map(allFiles.filter(f => [...EMITTERS, ".js"].includes(f.slice(f.lastIndexOf("."))))
  .map(f => [f, readFileSync(f, "utf8")]));

/**
 * A leaked name is dead when nothing outside the file that declares it ever
 * mentions it, so the name is pollution rather than an API: no other script
 * reads it and no page's inline handler calls it.
 *
 * @param {string} file the declaring script
 * @param {string} name the leaked name
 * @returns {boolean} true when no other file in the webapps mentions it
 */
function isDeadLeak(file, name) {
  const mention = new RegExp(`\\b${name.replace(/\$/g, "\\$")}\\b`);
  for (const [other, text] of corpus) {
    if (other !== file && mention.test(text)) {return false;}
  }
  return true;
}

/**
 * The dead leaks the audit has not cleaned up yet. The list may only shrink: a
 * name that appears on it is a new global reaching the window for nothing, and
 * a name that leaves it must be deleted here so it cannot come back.
 */
const DEAD_LEAK_BASELINE = new Set([]);

test("the leak extractors still find what they are looking for", () => {
  const src = `var topVar = 1;
let topLet = 2;
function topFn() {}
window.topWindowAssign = 3;
globalThis.topGlobalThisAssign = 4;
bareIndented = 5;
  window.indentedWindowAssign = 6;
  let indentedLet = 7;
  bareIndentedInside = 8;
`;
  assert.deepEqual(leakedNames(src),
    ["topVar", "topLet", "topFn", "topWindowAssign", "topGlobalThisAssign", "bareIndented"],
    "column-0 declarations, window exports and implicit globals are leaks; nested ones are not");

  assert.ok(isWorker("importScripts('/js/x.js');\nself.onmessage = () => {};", "aWorker.js"),
    "a worker is recognised, so its scope is not read as a page's");
  assert.ok(isWorker("self.onmessage = () => {};", "a.js"),
    "a worker that does not importScripts is still recognised");
  assert.ok(!isWorker("const w = new Worker('x.js');", "x.js"),
    "and the side that starts a worker is not");

  const dead = isDeadLeak(join(APPS, "routerconsole", "jsp", "js", "setupIframe.js"), "setupIframe");
  assert.equal(dead, false, "setupIframe is called from page markup, so it is not dead");
  // the scan's own scale: a walk or a matcher that quietly stops matching reads
  // as a clean tree, so pin what has to be visible for the rules to mean anything
  assert.ok(corpus.size > 700, `the reference scan only reads ${corpus.size} files`);
  assert.ok(classicScripts.size > 40, `only ${classicScripts.size} classic page scripts are known`);
  assert.ok(leaks.size > 0 && leaks.size < classicScripts.size,
    "some classic scripts still export a global, and most no longer do");
});

/** The scripts each emitter asks for, as page path to script path. */
const perPage = new Map();
for (const key of seen.keys()) {
  const [page, target] = key.split("\u0000");
  if (!perPage.has(page)) {perPage.set(page, new Set());}
  perPage.get(page).add(target);
}

if (process.env.COLLIDE) {
  for (const [page, set] of perPage) {
    const hit = [...set].filter(f => f.includes("toggle"));
    if (hit.length) {console.log("PAIR " + page.slice(APPS.length + 1) + " -> " + hit.map(f => f.slice(APPS.length + 1)).join(" "));}
  }
}

test("one page never loads two scripts that declare the same global", () => {
  // two classic scripts on one page declaring one name is not two variables: the
  // second declaration overwrites the first, so which copy runs depends on the
  // order the tags happen to be written in
  const violations = [];
  for (const [page, set] of perPage) {
    const byName = new Map();
    for (const script of set) {
      for (const name of leaks.get(script) ?? []) {
        if (!byName.has(name)) {byName.set(name, new Set());}
        byName.get(name).add(script);
      }
    }
    for (const [name, where] of byName) {
      if (where.size > 1) {
        violations.push(`${page.slice(APPS.length + 1)} declares ${name} in ` +
          [...where].map(w => w.slice(APPS.length + 1)).sort().join(" and "));
      }
    }
  }
  assert.deepEqual(violations.sort(), [], "\n" + violations.sort().join("\n"));
});

/**
 * Strips comments and string literals so an identifier scan does not fire on
 * prose. Regex literals are left alone: a name inside one is still prose, and a
 * scan that ate them would eat real code on the same line.
 *
 * @param {string} source the file's contents
 * @returns {string} the same text with comments and quoted strings blanked out
 */
function stripProse(source) {
  let out = source.replace(/\/\*[\s\S]*?\*\//g, "");
  out = out.replace(/\/\/[^\n]*/g, "");
  out = out.replace(/"(?:\\.|[^"\\])*"/g, '""');
  out = out.replace(/'(?:\\.|[^'\\])*'/g, "''");
  return out.replace(/`(?:\\.|[^`\\])*`/g, "``");
}

/**
 * A named IIFE binds its own name inside its body, which shadows the global of
 * that name: a script wrapped as `function progressx()` can no longer reach the
 * `window.progressx` it publishes itself, and the call reads as "x.hide is not a
 * function" from a line that looks untouched. Only the case where the script
 * also publishes that name is a defect - a wrapper named after nothing on the
 * window shadows nothing.
 *
 * @param {string} source the file's contents
 * @returns {Array<{name: string, line: string}>} every self-shadowed reference
 */
function shadowedByOwnIife(source) {
  const opener = /^\(function (\w+)\(\) \{/m.exec(source);
  if (opener === null) {return [];}
  const name = opener[1];
  // the wrapper only shadows something it also puts on the window, at any indent
  if (!new RegExp(`\\b(?:window|globalThis|self)\\.${name}\\s*=`).test(source)) {return [];}
  const body = stripProse(source.slice(opener.index + opener[0].length));
  // a reference that can only be an identifier: called, read as a property, or
  // passed as an argument - a word in prose or a selector string never looks like this
  const used = new RegExp(`(?:^|[=([{,;+&|!?<>])\\s*${name}(?:\\s*(?:\\.|\\()|\\s*[,)])`);
  return body.split("\n")
    .filter(line => used.test(line) && !/^\s*(?:\/?\*|\/\/)/.test(line))
    .map(line => ({name, line: line.trim()}));
}

test("no script is wrapped in an IIFE named after something it uses", () => {
  const violations = [];
  for (const file of classicScripts) {
    for (const {name, line} of shadowedByOwnIife(readFileSync(file, "utf8"))) {
      violations.push(`${file.slice(APPS.length + 1)}: IIFE ${name}() shadows it - ${line}`);
    }
  }
  assert.deepEqual(violations.sort(), [], "\n" + violations.sort().join("\n"));

  const shadowed = shadowedByOwnIife(`
(function progressx() {
  window.progressx = {hide: () => {}};
  window.addEventListener("load", () => { progressx.hide(); });
}());
`);
  assert.equal(shadowed.length, 1, "a wrapper named after the object it publishes is caught");
  assert.equal(shadowedByOwnIife('window.progressx = {};\nwindow.addEventListener("load", () => { progressx.hide(); });').length, 0,
    "and a script with no wrapper is not flagged");
});

test("a script leaks no global nothing references (ratchet)", () => {
  const fresh = [];
  for (const [file, names] of leaks) {
    // a script no page loads is not on the window either, and is covered by the
    // dead-code audit rather than this one
    if (!loadedScripts.has(file)) {continue;}
    for (const name of names) {
      if (isDeadLeak(file, name) && !DEAD_LEAK_BASELINE.has(name)) {
        fresh.push(`${name}  (${file.slice(APPS.length + 1)})`);
      }
    }
  }
  assert.deepEqual(fresh.sort(), [], "\n" + fresh.sort().join("\n"));
});
