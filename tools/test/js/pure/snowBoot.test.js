/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/snowflakes/snowboot.js — the
 * loader that decides whether the console's snow field is fetched at all.
 *
 * The field is four files, and a console page that never asks for them should not pay for
 * a fetch, a parse and a run of any of them. What this file pins is when they arrive:
 *
 *   - out of season, with nothing in the URL and nothing stored, nothing is injected
 *     at all — only a keydown listener;
 *   - the season (1 December to 6 January), ?snow=1 or a stored "on" each bring the field's
 *     two files at load, ?hud=1 or a stored card ask brings those two and the card's own
 *     module and stylesheet, and ?snow=0 or a stored "off" each stop that;
 *   - typing 'snow' or 'hud' brings them in, in the order they have to run in, and a
 *     gesture typed into a text field is not a gesture;
 *   - a gesture typed while the field is on the page is handed to the field instead of
 *     fetching it a second time, and switching the field off takes the scripts back off
 *     the page — elements detached, globals dropped, keystream watched again;
 *   - the card has its own stored key, separate from the field's, and closing it takes the
 *     card's module and stylesheet back off a page whose field is still running;
 *   - the decision rules live here and nowhere else: the page side reads them off
 *     window.snowBoot rather than carrying a copy.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const SRC = readFileSync(
  new URL("../../../../apps/routerconsole/jsp/js/snowflakes/snowboot.js", import.meta.url), "utf8");

const REAL_DATE = Date;

/**
 * A Date subclass pinned to a moment the caller chooses, so the season gate can be tested
 * in June.
 *
 * @param {string} iso the moment to pin the clock to
 * @returns {Function} a Date the loader cannot tell the difference from
 */
function pinnedDate(iso) {
  const at = new REAL_DATE(iso).getTime();
  class Pinned extends REAL_DATE {
    constructor(...args) {
      super(...(args.length ? args : [at]));
    }
    static now() {return at;}
  }
  return Pinned;
}

/**
 * Loads the loader in a context that imitates a browser page: a window with a location,
 * storage and matchMedia, a document with a head, a body and an element factory, and a
 * keydown listener slot the test can type into.
 *
 * @param {Object} [options] the environment to imitate
 * @param {string} [options.search=""] the page's query string
 * @param {string|null} [options.stored=null] what localStorage holds for snowMode
 * @param {string|null} [options.hud=null] what localStorage holds for snowHud
 * @param {boolean} [options.reduced=false] whether prefers-reduced-motion matches
 * @param {string} [options.date="2026-06-15T12:00:00"] what the clock reads
 * @returns {{boot: Object, appended: Object[], listeners: Object, store: Map,
 *   scripts: function(): string[], names: function(): string[], fire: function(Object,
 *   string): void, type: function(string): void}} the loader's API plus what it touched, a
 *   way to say a file arrived, and a way to type at it
 */
function openBoot(options = {}) {
  const listeners = {};
  const appended = [];
  const store = new Map();
  if (typeof options.stored === "string") {store.set("snowMode", options.stored);}
  if (typeof options.hud === "string") {store.set("snowHud", options.hud);}

  /**
   * An element the loader can inject, attach and detach again.
   *
   * @param {string} tag the tag name asked for
   * @returns {Object} an element stub that reports where it is attached
   */
  const element = tag => ({
    tagName: tag.toUpperCase(),
    rel: "",
    href: "",
    src: "",
    async: true,
    style: {},
    parentNode: null,
    events: {},
    addEventListener: function (type, fn) {(this.events[type] = this.events[type] || []).push(fn);},
    setAttribute: () => {}
  });

  const head = {
    appendChild: el => {el.parentNode = head; appended.push(el);},
    removeChild: el => {el.parentNode = null;}
  };
  const body = {
    appendChild: el => {el.parentNode = body; appended.push(el);},
    removeChild: el => {el.parentNode = null;}
  };

  const ctx = {
    window: {
      location: {search: options.search || ""},
      localStorage: {
        getItem: k => (store.has(k) ? store.get(k) : null),
        setItem: (k, v) => {store.set(k, String(v));}
      },
      matchMedia: () => ({matches: !!options.reduced})
    },
    document: {
      head,
      body,
      currentScript: {src: "http://localhost:7657/js/snowflakes/snowboot.js"},
      createElement: element,
      addEventListener: (type, fn) => {listeners[type] = fn;},
      removeEventListener: type => {delete listeners[type];}
    },
    URLSearchParams,
    Date: pinnedDate(options.date || "2026-06-15T12:00:00")
  };
  ctx.self = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);
  return {
    boot: ctx.window.snowBoot,
    window: ctx.window,
    appended,
    listeners,
    store,
    scripts: () => appended.filter(el => el.tagName === "SCRIPT").map(el => el.src),
    names: () => appended.filter(el => el.tagName === "SCRIPT").map(el => el.src.split("/").pop()),
    sheets: () => appended.filter(el => el.tagName === "LINK"),
    /**
     * Says one injected file arrived, the way the browser would tell the loader.
     *
     * @param {Object} el the element from appended
     * @param {string} type the event to fire on it, e.g. "load"
     * @returns {void}
     */
    fire: (el, type) => {(el.events[type] || []).forEach(fn => fn.call(el, {type}));},
    /**
     * Types one keystroke at the page.
     *
     * @param {string} key the key as a keydown event reports it
     * @param {Object} [over] fields to put on the event, e.g. a target tag name
     * @returns {void}
     */
    type: (key, over = {}) => {
      listeners.keydown(Object.assign({key, target: {tagName: "BODY"}}, over));
    }
  };
}

/**
 * Types a whole gesture, one keystroke at a time.
 *
 * @param {Object} page the page from openBoot()
 * @param {string} text the gesture to type
 * @param {Object} [over] fields to put on every keystroke, e.g. a target tag name
 * @returns {void}
 */
const word = (page, text, over = {}) => {for (const key of text) {page.type(key, over);}};

test("out of season, with nothing asked for, the loader fetches nothing", () => {
  const page = openBoot();
  assert.deepEqual(page.scripts(), [], "no script is injected in June");
  assert.equal(page.appended.length, 0, "not even the card's stylesheet");
  assert.equal(typeof page.listeners.keydown, "function",
               "and the only thing it costs the page is a keydown listener");
});

test("the season, the query and a stored choice each bring the field in", () => {
  const december = openBoot({date: "2026-12-25T12:00:00"});
  assert.deepEqual(december.names(), ["snowman.js", "snowflakes.js"], "December loads the field");
  assert.deepEqual(openBoot({search: "?snow=1"}).names(), ["snowman.js", "snowflakes.js"],
                   "?snow=1 loads it");
  assert.deepEqual(openBoot({stored: "on"}).names(), ["snowman.js", "snowflakes.js"],
                   "so does what the reader left");
  assert.deepEqual(openBoot({search: "?hud=1"}).names(),
                   ["hud.js", "snowman.js", "snowflakes.js"],
                   "and asking for the card brings its module along with the field");
});

test("?snow=0 and a stored choice of off keep even a December page quiet", () => {
  assert.equal(openBoot({date: "2026-12-25T12:00:00", search: "?snow=0"}).scripts().length, 0,
               "the URL beats the calendar");
  assert.equal(openBoot({date: "2026-12-25T12:00:00", stored: "off"}).scripts().length, 0,
               "and the reader's own choice beats it too");
  assert.equal(openBoot({date: "2026-12-25T12:00:00", reduced: true}).scripts().length, 0,
               "so does a request for reduced motion");
});

test("the scripts are injected in the order they have to run in", () => {
  const page = openBoot({search: "?hud=1"});
  assert.deepEqual(page.names(),
                   ["hud.js", "snowman.js", "snowflakes.js"],
                   "the card and the worker it draws from are defined before the page side runs");
  assert.deepEqual(page.scripts(),
                   ["http://localhost:7657/js/snowflakes/hud.js",
                    "http://localhost:7657/js/snowflakes/snowman.js",
                    "http://localhost:7657/js/snowflakes/snowflakes.js"],
               "and they are served from beside the loader, not from a hardcoded path");
  assert.ok(page.scripts().every(src => src), "every injected script names a file");
});

test("the card's own two files arrive only when the card is wanted", () => {
  const field = openBoot({search: "?snow=1"});
  assert.equal(field.sheets().length, 0, "a field nobody opens the card on never fetches its colours");
  assert.ok(!field.names().includes("hud.js"), "nor the card's module");
  const asked = openBoot({search: "?hud=1"});
  const sheet = asked.appended.find(el => el.tagName === "LINK");
  assert.ok(sheet && sheet.href.endsWith("hud.css"), "?hud=1 asks for the card, so it arrives");
  assert.ok(asked.appended.indexOf(sheet) < asked.appended.findIndex(el => el.tagName === "SCRIPT"),
            "and it is on the page before the scripts that depend on it");

  // A card the reader left open is a card wanted on this page, and it brings the field with
  // it: the card drives the field, and the calendar alone says nothing at all in June.
  const left = openBoot({hud: "on"});
  assert.deepEqual(left.names(), ["hud.js", "snowman.js", "snowflakes.js"],
                   "a stored card ask loads the card and the field out of season");
  assert.equal(left.sheets().length, 1, "with its stylesheet");
  assert.equal(openBoot({date: "2026-12-25T12:00:00", hud: "off"}).sheets().length, 0,
               "and a stored choice of off keeps the card off even a December page");
});

test("typing the gesture brings the field in, and remembers the ask", () => {
  const page = openBoot();
  word(page, "snow");
  assert.deepEqual(page.names(), ["snowman.js", "snowflakes.js"], "typing 'snow' loads the field");
  assert.equal(page.store.get("snowMode"), "on", "and remembers it for the next page");
  assert.equal(page.store.get("snowHud"), undefined,
               "while the card's own key is left exactly where it was");
  assert.equal(page.boot.takePending(), "snow", "the page side is told what was asked for");

  const card = openBoot();
  word(card, "hud");
  assert.deepEqual(card.names(), ["hud.js", "snowman.js", "snowflakes.js"],
                   "typing 'hud' loads the field as well");
  assert.equal(card.store.get("snowHud"), "on", "and remembers the card in the card's own key");
  assert.equal(card.store.get("snowMode"), undefined,
               "without putting a field choice on the record the reader never made");
  assert.equal(card.boot.takePending(), "hud", "and says the card was what was wanted");
});

test("a gesture typed into a text field is not a gesture", () => {
  const page = openBoot();
  word(page, "snow", {target: {tagName: "INPUT"}});
  word(page, "hud", {target: {isContentEditable: true}});
  assert.deepEqual(page.scripts(), [], "a reader typing 'snow' into a filter asked for nothing");
});

test("one gesture never loads the field twice", () => {
  const page = openBoot();
  word(page, "snow");
  word(page, "snow");
  assert.equal(page.scripts().length, 2, "the second gesture is answered by the field, not by a fetch");
});

test("switching the field off takes the scripts back off the page", () => {
  const page = openBoot();
  word(page, "snow");
  const injected = page.appended.slice();
  assert.equal(injected.length, 2, "two scripts are on the page, and no sheet the card never opened");

  // The field answers the next gesture once it is loaded, and its own toggle hands the
  // unload back to the loader — that is what snowflakes.js does.
  page.window.snowGates = {
    toggle: () => {page.boot.unload();},
    shutdown: () => {page.window.snowGates = null;}
  };
  word(page, "snow");
  assert.ok(injected.every(el => el.parentNode === null),
            "every element the loader injected is detached");
  assert.equal(page.window.snowGates, undefined, "and the globals it published are gone");
  assert.equal(typeof page.listeners.keydown, "function",
               "the keystream is watched again, so the next gesture can bring the field back");
  word(page, "snow");
  assert.equal(page.scripts().length, 4, "and it does");
});

test("closing the card takes its two files off and leaves the field running", () => {
  const page = openBoot({search: "?hud=1"});
  const before = page.appended.slice();
  assert.deepEqual(before.map(el => el.tagName), ["LINK", "SCRIPT", "SCRIPT", "SCRIPT"],
                   "the sheet comes first, then the card, then the field it drives");
  page.window.SNOWHUD = {};
  let dropped = 0;
  page.window.snowGates = {dropHud: () => {dropped++;}};

  // That is what the card's own close button does: it hands the click to the loader.
  page.boot.closeHud();

  assert.equal(page.store.get("snowHud"), "off", "the card's own key says the reader shut it");
  assert.equal(page.store.get("snowMode"), undefined,
               "and the field's key is untouched, so the snow is left exactly as it was");
  const card = before.filter(el => el.href.endsWith("hud.css") || el.src.endsWith("hud.js"));
  assert.equal(card.length, 2, "the card's stylesheet and its module are the two in question");
  assert.ok(card.every(el => el.parentNode === null), "and both come off the page at once");
  const field = before.filter(el => !el.href.endsWith("hud.css") && !el.src.endsWith("hud.js"));
  assert.ok(field.every(el => el.parentNode !== null),
            "while the worker and the page side stay on, running or not running as they were");
  assert.equal(dropped, 1, "the page side is told to take the card's element off too");
  assert.equal(page.window.SNOWHUD, undefined, "and the global it published is gone with it");
});

test("a card shut on this page is not on the next one, and a gesture brings it back", () => {
  const page = openBoot({hud: "on"});
  page.window.SNOWHUD = {};
  page.window.snowGates = {openHud: () => {page.window.hudOpened = (page.window.hudOpened || 0) + 1;}};
  page.boot.closeHud();
  assert.equal(page.boot.hudMode(), false, "the stored answer for the next page is no card");

  // Typing the gesture again is the reader changing their mind: the module and the sheet come
  // back, and the card opens only once both have landed, never as an unstyled panel.
  word(page, "hud");
  assert.equal(page.window.hudOpened, undefined, "nothing opens while the files are still in flight");
  const fresh = page.appended.filter(el => el.parentNode !== null);
  assert.ok(fresh.some(el => el.href.endsWith("hud.css")), "the stylesheet is fetched again");
  const script = fresh.find(el => el.src.endsWith("hud.js"));
  assert.ok(script, "and so is the module");
  page.window.SNOWHUD = {};
  page.fire(script, "load");
  assert.equal(page.window.hudOpened, undefined, "and still nothing before the colours arrive");
  page.fire(fresh.find(el => el.href.endsWith("hud.css")), "load");
  assert.equal(page.window.hudOpened, 1, "then the card opens, styled");
  assert.equal(page.store.get("snowHud"), "on", "and the reopened card is remembered again");
});

test("the decision rules are exported for the page side to read", () => {
  const page = openBoot();
  const yes = ["2026-12-01T00:00:01", "2027-01-06T23:59:59"];
  const no = ["2026-11-30T23:59:59", "2027-01-07T00:00:01"];
  for (const iso of yes) {assert.equal(page.boot.inSeason(new REAL_DATE(iso)), true, `${iso} is snow`);}
  for (const iso of no) {assert.equal(page.boot.inSeason(new REAL_DATE(iso)), false, `${iso} is not`);}
  assert.equal(page.boot.modeFor({url: null, stored: "on", reduced: true, season: false}), "on",
               "an explicit choice beats reduced motion");
  assert.equal(page.boot.modeFor({url: "off", stored: "on", reduced: false, season: true}), "off",
               "and the URL beats the stored choice");
  assert.equal(openBoot({search: "?snow=on"}).boot.urlMode(), "on", "the query is read");
  assert.equal(openBoot({search: "?hud=on"}).boot.hudRequested(), true, "and so is the card ask");
  assert.equal(openBoot({hud: "on"}).boot.hudMode(), true, "the card's stored choice is read");
  assert.equal(openBoot({search: "?hud=off", hud: "on"}).boot.hudMode(), false,
               "and the URL overrules it for one page");
});
