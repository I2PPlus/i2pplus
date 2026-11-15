/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/snowflakes/snowboot.js — the
 * loader that decides whether the console's snow field is fetched at all.
 *
 * The field is four files, and a console page that never asks for them should not pay for
 * a fetch, a parse and a run of any of them. What this file pins is when they arrive:
 *
 *   - out of season, with nothing in the URL and nothing stored, nothing is injected
 *     at all — only a keydown listener;
 *   - the season (1 December to 6 January), ?snow=1, ?hud=1 or a stored "on" each
 *     inject them at load, and ?snow=0 or a stored "off" each stop that;
 *   - typing 'snow' or 'hud' injects them, in the order they have to run in, and a
 *     gesture typed into a text field is not a gesture;
 *   - a gesture typed while the field is on the page is handed to the field instead of
 *     fetching it a second time, and switching the field off takes the scripts back off
 *     the page — elements detached, globals dropped, keystream watched again;
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
 * @param {boolean} [options.reduced=false] whether prefers-reduced-motion matches
 * @param {string} [options.date="2026-06-15T12:00:00"] what the clock reads
 * @returns {{boot: Object, appended: Object[], listeners: Object, store: Map,
 *   scripts: function(): string[], type: function(string): void}} the loader's API plus
 *   what it touched and a way to type at it
 */
function openBoot(options = {}) {
  const listeners = {};
  const appended = [];
  const store = new Map();
  if (typeof options.stored === "string") {store.set("snowMode", options.stored);}

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
    addEventListener: () => {},
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
  assert.equal(december.scripts().length, 3, "December loads the field");
  assert.equal(openBoot({search: "?snow=1"}).scripts().length, 3, "?snow=1 loads it");
  assert.equal(openBoot({search: "?hud=1"}).scripts().length, 3, "?hud=1 loads it too");
  assert.equal(openBoot({stored: "on"}).scripts().length, 3, "so does what the reader left");
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
  const page = openBoot({search: "?snow=1"});
  assert.deepEqual(page.scripts().map(src => src.split("/").pop()),
                   ["hud.js", "snowman.js", "snowflakes.js"],
                   "the card and the worker it draws from are defined before the page side runs");
  assert.deepEqual(page.scripts(),
                   ["http://localhost:7657/js/snowflakes/hud.js",
                    "http://localhost:7657/js/snowflakes/snowman.js",
                    "http://localhost:7657/js/snowflakes/snowflakes.js"],
               "and they are served from beside the loader, not from a hardcoded path");
  assert.ok(page.scripts().every(src => src), "every injected script names a file");
});

test("the card's stylesheet arrives only when the card is wanted", () => {
  const field = openBoot({search: "?snow=1"});
  assert.equal(field.appended.filter(el => el.tagName === "LINK").length, 0,
               "a field nobody opens the card on never fetches the card's colours");
  const asked = openBoot({search: "?hud=1"});
  const sheet = asked.appended.find(el => el.tagName === "LINK");
  assert.ok(sheet && sheet.href.endsWith("hud.css"), "?hud=1 asks for the card, so it arrives");
  assert.ok(asked.appended.indexOf(sheet) < asked.appended.findIndex(el => el.tagName === "SCRIPT"),
            "and it is on the page before the scripts that depend on it");

  // Opening the card later, on a page that loaded the field without it, is the page side
  // asking the loader for the sheet — once, however many times the card is opened.
  const late = openBoot({search: "?snow=1"});
  late.type("x");
  late.boot.loadSheet();
  late.boot.loadSheet();
  assert.equal(late.appended.filter(el => el.tagName === "LINK").length, 1,
               "and it is put on the page once");
  late.boot.unload();
  assert.equal(late.appended.filter(el => el.tagName === "LINK" && el.parentNode).length, 0,
               "and it comes back off with the rest of the field");
});

test("typing the gesture brings the field in, and remembers the ask", () => {
  const page = openBoot();
  word(page, "snow");
  assert.equal(page.scripts().length, 3, "typing 'snow' loads the field");
  assert.equal(page.store.get("snowMode"), "on", "and remembers it for the next page");
  assert.equal(page.boot.takePending(), "snow", "the page side is told what was asked for");

  const card = openBoot();
  word(card, "hud");
  assert.equal(card.scripts().length, 3, "typing 'hud' loads the field as well");
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
  assert.equal(page.scripts().length, 3, "the second gesture is answered by the field, not by a fetch");
});

test("switching the field off takes the scripts back off the page", () => {
  const page = openBoot();
  word(page, "snow");
  const injected = page.appended.slice();
  assert.equal(injected.length, 3, "three scripts are on the page, and no sheet the card never opened");

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
  assert.equal(page.scripts().length, 6, "and it does");
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
});
