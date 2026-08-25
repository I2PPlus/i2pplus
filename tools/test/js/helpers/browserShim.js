/**
 * @file browserShim.js - Minimal browser globals so webapp scripts can be imported
 * under Node for smoke testing. Installed by runOne.mjs in a fresh child process per
 * target file: every module is evaluated against pristine globals, and nothing leaks
 * between files.
 *
 * Philosophy: real DOM semantics via linkedom for document-level access, inert stubs
 * for browser platform APIs (workers, observers, storage, media), and a deep no-op
 * proxy for extension/platform namespaces (chrome.*, browser.*) whose shape we do not
 * model. Nothing here schedules work: timers are recorded but never fire, so module
 * side effects cannot keep the event loop alive or re-enter during evaluation.
 *
 * @license AGPL3 or later
 */

import { parseHTML } from "linkedom";

/**
 * Creates a self-referential no-op: any property access yields another noop,
 * any call yields another noop (fluent chains never NPE), iteration yields an
 * empty list, primitives coerce to "", and `await` on it never treats it as a
 * thenable. Used for chrome/browser extension namespaces and unknown APIs.
 *
 * @returns {Function} the proxy root
 */
function deepNoop() {
  const fn = function () {};
  return new Proxy(fn, {
    get(target, prop) {
      if (prop === Symbol.toPrimitive || prop === "toString" || prop === "valueOf") {return () => "";}
      if (prop === "then") {return undefined;}
      if (prop === Symbol.iterator) {return [][Symbol.iterator];}
      if (prop === "length") {return 0;}
      if (prop === "forEach") {return () => {};}
      if (prop === "map" || prop === "filter") {return () => [];}
      return deepNoop();
    },
    apply: () => deepNoop()
  });
}

/**
 * Installs the shim onto globalThis. Idempotent per process; each smoke-test child
 * calls this once before importing its target module.
 *
 * @returns {void}
 */
export function installBrowserShim() {
  if (globalThis.__browserShimInstalled) {return;}
  globalThis.__browserShimInstalled = true;

  const { window } = parseHTML("<!DOCTYPE HTML><html><head></head><body></body></html>");

  // Recorded-but-never-fired timers: modules registering intervals at import time
  // must not retain the event loop or execute during evaluation.
  let timerId = 0;
  const timerStub = () => ++timerId;

  const storage = () => {
    const map = new Map();
    return {
      getItem: k => (map.has(k) ? map.get(k) : null),
      setItem: (k, v) => {map.set(String(k), String(v));},
      removeItem: k => {map.delete(k);},
      clear: () => {map.clear();},
      key: i => [...map.keys()][i] ?? null,
      get length() {return map.size;}
    };
  };

  const location = {
    href: "http://127.0.0.1:7657/i2psnark/",
    origin: "http://127.0.0.1:7657",
    host: "127.0.0.1:7657",
    hostname: "127.0.0.1",
    port: "7657",
    protocol: "http:",
    pathname: "/i2psnark/",
    search: "",
    hash: "",
    assign: () => {},
    replace: () => {},
    reload: () => {}
  };

  class PortStub extends Array {}
  Object.assign(PortStub.prototype, {
    postMessage() {},
    start() {},
    close() {},
    addEventListener(type, fn) {this.push(fn);}
  });

  class WorkerStub {
    constructor() {this.port = new PortStub();}
    postMessage() {}
    terminate() {}
    addEventListener() {}
    removeEventListener() {}
  }

  class BroadcastChannelStub {
    constructor() {}
    addEventListener() {}
    removeEventListener() {}
    postMessage() {}
    close() {}
  }

  class ObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  class MediaQueryStub {
    constructor() {
      this.matches = false;
      this.onchange = null;
    }
    addEventListener() {}
    removeEventListener() {}
    addListener() {}
    removeListener() {}
    addListenerOnce() {}
    dispatchEvent() {return false;}
  }

  /**
   * An element-shaped never-null: property reads yield inert no-ops, writes are
   * accepted, calls yield another inert element. Returned by document lookups that
   * miss, so modules running page-specific init at import time do not crash on
   * markup the shim's empty document does not contain. The proxy is truthy, which
   * is exactly what callers of these lookups assume on live pages.
   *
   * @returns {Function} the inert element proxy
   */
  function inertElement() {
    const store = {
      style: {},
      dataset: {},
      classList: {add() {}, remove() {}, toggle() {}, contains() {return false;}}
    };
    const fn = function () {};
    return new Proxy(fn, {
      get(target, prop) {
        if (prop in store) {return store[prop];}
        if (prop === "length") {return 0;}
        if (prop === "then") {return undefined;}
        if (prop === Symbol.iterator) {return [][Symbol.iterator];}
        if (prop === "forEach") {return () => {};}
        if (prop === "map" || prop === "filter") {return () => [];}
        if (prop === Symbol.toPrimitive || prop === "toString" || prop === "valueOf") {return () => "";}
        return deepNoop();
      },
      set(target, prop, value) {store[prop] = value; return true;},
      apply() {return inertElement();}
    });
  }

  // Cookie access (linkedom leaves document.cookie undefined).
  try {
    Object.defineProperty(window.document, "cookie", {get: () => "", set: () => {}, configurable: true});
  } catch {}

  // Never-null lookups at document level.
  try {
    const shimDoc = window.document;
    const byId = shimDoc.getElementById.bind(shimDoc);
    shimDoc.getElementById = id => byId(id) || inertElement();
    const q = shimDoc.querySelector.bind(shimDoc);
    shimDoc.querySelector = sel => q(sel) || inertElement();
  } catch {}

  // DedicatedWorkerGlobalScope stand-in for worker scripts (`self.postMessage`).
  const selfStub = {
    postMessage() {},
    addEventListener() {},
    removeEventListener() {},
    close() {},
    onmessage: null,
    location
  };

  // Globals normally injected inline by console JSPs before the module loads.
  const jspGlobals = {
    refresh: 15,
    toggle: {},
    theme: "light",
    stickySidebarEnabled: false,
    // console progress bar helper used by peers.js
    progressx: {show() {}, progress() {}, hide() {}},
    Tablesort: class {static extend() {}}
  };
  // tablesort.js registers one pattern/comparator pair per collation, all injected
  // by the page that hosts it.
  for (const kind of ["number", "date", "dotsep", "filesize", "monthname", "natural", "intl"]) {
    jspGlobals[kind + "Pattern"] = () => false;
    jspGlobals[kind + "CmpEL"] = () => 0;
  }

  const globals = {
    window,
    document: window.document,
    Node: window.Node,
    Element: window.Element,
    HTMLElement: window.HTMLElement,
    DocumentFragment: window.DocumentFragment,
    MutationObserver: window.MutationObserver || ObserverStub,
    DOMParser: window.DOMParser,
    CustomEvent: window.CustomEvent || window.Event,
    Event: window.Event,
    location,
    navigator: {userAgent: "i2p-js-smoke", onLine: true},
    history: {replaceState() {}, pushState() {}, back() {}, forward() {}, go() {}},
    localStorage: storage(),
    sessionStorage: storage(),
    requestAnimationFrame: cb => timerStub(),
    cancelAnimationFrame: () => {},
    matchMedia: () => new MediaQueryStub(),
    getComputedStyle: () => ({getPropertyValue: () => "", width: "0px", height: "0px"}),
    Worker: WorkerStub,
    SharedWorker: WorkerStub,
    BroadcastChannel: BroadcastChannelStub,
    IntersectionObserver: ObserverStub,
    ResizeObserver: ObserverStub,
    PerformanceObserver: ObserverStub,
    Image: class {constructor() {}},
    Audio: class {constructor() {}},
    scrollTo() {},
    scrollBy() {},
    alert() {},
    confirm() {return false;},
    prompt() {return null;},
    importScripts() {},
    setTimeout: timerStub,
    setInterval: timerStub,
    clearTimeout: () => {},
    clearInterval: () => {},
    queueMicrotask,
    structuredClone,
    fetch: () => new Promise(() => {}),
    chrome: deepNoop(),
    browser: deepNoop(),
    self: selfStub,
    ...jspGlobals
  };

  // Window self-references used by iframe-aware modules.
  try {
    window.parent = window;
    window.top = window;
    window.self = window;
  } catch {}

  /**
   * Keys that must always replace any existing global: Node ships real
   * implementations of the timer/fetch/BroadcastChannel family, and leaving them
   * in place means module-scope timers and channels keep the event loop alive and
   * fire callbacks mid-suite.
   */
  const FORCE = new Set([
    "location", "setTimeout", "setInterval", "clearTimeout", "clearInterval",
    "requestAnimationFrame", "cancelAnimationFrame", "fetch",
    "BroadcastChannel",
    // linkedom's real MutationObserver chokes on inert-element observe targets;
    // the inert stub accepts anything.
    "MutationObserver",
    // DedicatedWorkerGlobalScope alias; must not be clobbered by window mirroring.
    "self"
  ]);

  for (const [key, value] of Object.entries(globals)) {
    if (FORCE.has(key) || !(key in globalThis)) {
      try {globalThis[key] = value;} catch {}
    }
  }
  // Mirror onto the shimmed window so `window.x` and bare `x` agree. MutationObserver
  // too: modules reaching it via window.MutationObserver must get the inert stub.
  for (const key of ["location", "localStorage", "sessionStorage", "navigator", "history", "fetch"]) {
    try {window[key] = globals[key];} catch {}
  }
  try {window.MutationObserver = ObserverStub;} catch {}
  try {Object.defineProperty(window.document, "location", {get: () => location, set: () => {}, configurable: true});} catch {}
}
