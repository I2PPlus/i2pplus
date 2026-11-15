/**
 * @module snowboot
 * @description The snow field's loader, and the only snow script a console page loads
 * by itself. The field is four files — the worker, the tuning card, its stylesheet and
 * the page side that runs them — and for eleven months of the year nothing on the page
 * asks for any of them, so loading them costs a fetch, a parse and a run on every
 * console page view for nothing. This file costs a keydown listener, and puts the rest
 * on the page only when something has asked for snow: the season (all of December and
 * 1-6 January, in the viewer's own calendar), the query (?snow=1 / ?snow=on, or ?hud=1,
 * which is asking to see the field), what the reader left the last page in localStorage,
 * or a typed gesture — 'snow' for the field, 'hud' for the card, Ctrl+Alt+S for the
 * field as well.
 *
 * When the last of those stops holding, the scripts it loaded are taken back off the
 * page and the listener is put back, so the next gesture can bring them in.
 *
 * The field and the card are two decisions, kept in two localStorage keys (snowMode and
 * snowHud), because they are two things a reader does: the field is left on for the season,
 * while the card is opened and shut, and the card's own close button takes the card's module
 * and its stylesheet back off the page straight away, leaving the snow exactly as it was.
 *
 * The decision rules live here and nowhere else: snowflakes.js reads them from
 * window.snowBoot rather than carrying a second copy that can drift.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function snowboot() {
  'use strict';

  /** localStorage key holding the visitor's own choice about the field: "on" or "off". */
  const LS_KEY = "snowMode";
  /**
   * localStorage key holding the visitor's own choice about the tuning card: "on" or "off".
   *
   * A separate key on purpose. The card is something the reader opens and shuts, and
   * shutting it takes its two files back off the page while the field may well carry on
   * running; the field is something they leave on for the season. One key for both means
   * one choice, and a card shut on one page then decides what the next page fetches —
   * which is how a card shows up with its module loaded and its stylesheet not.
   */
  const HUD_KEY = "snowHud";
  /**
   * The season is the browser's local calendar, not the router's: a festive effect follows
   * the calendar the viewer is looking at. All of December, and 1-6 January.
   */
  const SEASON_FIRST_MONTH = 11;
  const SEASON_LAST_MONTH = 0;
  const SEASON_LAST_DAY = 6;
  /** Typed gesture that asks for the field. */
  const FIELD_SEQUENCE = "snow";
  /** Typed gesture that asks for the tuning card, and with it the field. */
  const HUD_SEQUENCE = "hud";
  /** How long two keystrokes may be apart and still be one gesture, in milliseconds. */
  const GESTURE_WINDOW_MS = 2000;
  /**
   * What the field is: the worker, then the page side that runs it — which starts the
   * field as soon as it runs, so the worker has to be defined already.
   */
  const FIELD_SCRIPTS = ["snowman.js", "snowflakes.js"];
  /**
   * The tuning card's module, loaded only when the card is wanted, and always before the
   * page side: starting a field attaches the card, so the card has to be defined by then.
   */
  const HUD_SCRIPT = "hud.js";
  /** The card's stylesheet, injected with the card rather than on every page. */
  const SHEET = "hud.css";
  /** The globals the loaded scripts publish, cleared again on unload. */
  const GLOBALS = ["SNOWMAN", "SNOWHUD", "snowGates"];

  /** The elements this file injected, in injection order, empty until it loads anything. */
  const injected = [];
  /** The card's own elements — its module and its stylesheet — so closing it can take just
      those back off a page whose field is still running. */
  const card = [];
  /** The card's stylesheet element, or null while the card has never been on the page. */
  let sheet = null;
  /** Set once the sheet has landed or refused to; after that an open never waits. */
  let sheetReady = false;
  /** What to run when the sheet has landed or failed, in the order they asked. */
  const sheetWaiters = [];
  /** The gesture positions: each sequence has its own place in the keystream. */
  let fieldIndex = 0;
  let hudIndex = 0;
  /** When the last keystroke arrived, so a stale half-gesture does not complete later. */
  let at = 0;
  /** What the gesture that triggered the load asked for, read by the page side once. */
  let pending = null;
  /** Set while the scripts are in flight, so a second gesture cannot load them twice. */
  let loading = false;

  /**
   * Whether a date falls inside the snow season: all of December, and 1-6 January.
   *
   * @param {Date} date the moment to test
   * @returns {boolean} true when snow should run by the calendar alone
   */
  function inSeason(date) {
    const month = date.getMonth();
    if (month === SEASON_FIRST_MONTH) {return true;}
    return month === SEASON_LAST_MONTH && date.getDate() <= SEASON_LAST_DAY;
  }

  /**
   * Resolves the gates into a decision. An explicit choice beats the calendar, and a URL
   * flag beats a stored choice so a single page can be shown with or without snow without
   * changing what the browser remembers.
   *
   * @param {Object} flags the gate inputs
   * @param {string|null} flags.url "on" or "off" from ?snow=, else null
   * @param {string|null} flags.stored "on" or "off" from localStorage, else null
   * @param {boolean} flags.reduced whether prefers-reduced-motion matches
   * @param {boolean} flags.season whether the date is inside the season
   * @returns {string} "on" or "off"
   */
  function modeFor(flags) {
    if (flags.url === 'on') {return 'on';}
    if (flags.url === 'off') {return 'off';}
    if (flags.stored === 'on') {return 'on';}
    if (flags.stored === 'off') {return 'off';}
    if (flags.reduced) {return 'off';}
    return flags.season ? 'on' : 'off';
  }

  /**
   * Reads a one-page override from the query string: ?snow=1, ?snow=on, ?snow=0 or
   * ?snow=off.
   *
   * @returns {string|null} "on", "off", or null when the URL says nothing
   */
  function urlMode() {
    const query = queryOf();
    if (query === null || query.indexOf('snow=') === -1) {return null;}
    const value = param(query, 'snow');
    if (value === '1' || value === 'on') {return 'on';}
    return value === '0' || value === 'off' ? 'off' : null;
  }

  /**
   * Whether the query string asks for the tuning HUD: ?hud=1 or ?hud=on. Asking for the
   * card is asking to see the field, whatever the calendar says.
   *
   * @returns {boolean} true when the URL asks for the card
   */
  function hudRequested() {
    const query = queryOf();
    if (query === null) {return false;}
    const value = param(query, 'hud');
    return value === '1' || value === 'on';
  }

  /**
   * The page's query string, or null when there is no location to read it from.
   *
   * @returns {string|null} the query including its ?, or null
   */
  function queryOf() {
    try {
      return window.location.search || '';
    } catch (e) {
      return null;
    }
  }

  /**
   * One query parameter, or null when the query cannot be parsed at all.
   *
   * @param {string} query the query string including its ?
   * @param {string} name the parameter to read
   * @returns {string|null} its value, or null
   */
  function param(query, name) {
    try {
      return new URLSearchParams(query).get(name);
    } catch (e) {
      return null;
    }
  }

  /**
   * Reads the visitor's stored choice, ignoring anything that is not one of the two values
   * this file writes.
   *
   * @returns {string|null} "on", "off", or null when nothing is stored
   */
  function storedMode() {
    let value = null;
    try {
      value = window.localStorage.getItem(LS_KEY);
    } catch (e) {
      return null;
    }
    return value === 'on' || value === 'off' ? value : null;
  }

  /**
   * Remembers the visitor's choice. Storage is not guaranteed — private browsing throws on
   * write — and a failure only costs the memory, not the effect.
   *
   * @param {string} value "on" or "off"
   * @returns {void}
   */
  function storeMode(value) {
    try {
      window.localStorage.setItem(LS_KEY, value);
    } catch (e) {
      /* nothing to remember it in */
    }
  }

  /**
   * Whether the tuning card is wanted on this page: ?hud=1 or ?hud=on says yes for this
   * page alone, ?hud=0 or ?hud=off says no for this page alone, and otherwise what the
   * reader left the last time the card was opened or shut.
   *
   * @returns {boolean} true when this page should carry the card
   */
  function hudMode() {
    const query = queryOf();
    const value = query === null ? null : param(query, 'hud');
    if (value === '1' || value === 'on') {return true;}
    if (value === '0' || value === 'off') {return false;}
    return storedHud();
  }

  /**
   * The visitor's stored choice about the card, which is not the field's choice: a reader
   * who shut the card may well be running the field, and one who never opened it may be
   * running the field too.
   *
   * @returns {boolean} true when the reader left the card open
   */
  function storedHud() {
    let value = null;
    try {
      value = window.localStorage.getItem(HUD_KEY);
    } catch (e) {
      return false;
    }
    return value === 'on';
  }

  /**
   * Remembers the visitor's choice about the card. Storage is not guaranteed, and a failure
   * only costs the memory.
   *
   * @param {boolean} on true to leave the card on for the next page
   * @returns {void}
   */
  function storeHud(on) {
    try {
      window.localStorage.setItem(HUD_KEY, on ? 'on' : 'off');
    } catch (e) {
      /* nothing to remember it in */
    }
  }

  /**
   * Whether the visitor has asked the system for reduced motion.
   *
   * @returns {boolean} true when reduced motion is requested
   */
  function prefersReducedMotion() {
    if (typeof window.matchMedia !== 'function') {return false;}
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches === true;
  }

  /**
   * The mode this page load resolves to, before any gesture is typed.
   *
   * @returns {string} "on" or "off"
   */
  function decide() {
    return modeFor({url: urlMode() || (hudRequested() ? 'on' : null), stored: storedMode(),
                    reduced: prefersReducedMotion(), season: inSeason(new Date())});
  }

  /**
   * The directory this loader was served from, so the files it loads travel with it.
   *
   * @returns {string} a path ending in /
   */
  function baseDir() {
    const script = document.currentScript;
    const src = script && script.src ? script.src : '';
    const at = src.lastIndexOf('/');
    return at === -1 ? '/js/snowflakes/' : src.slice(0, at + 1);
  }

  /**
   * Puts the field's scripts on the page, in the order they have to run in.
   *
   * async=false is what keeps that order: a script inserted at runtime is async by default,
   * and a field whose page side runs before the worker it drives is a field that never
   * starts.
   *
   * @param {string} [asked] what the gesture asked for, read once by the page side
   * @returns {void}
   */
  function load(asked) {
    if (loading || isLoaded()) {return;}
    loading = true;
    pending = asked || null;
    // The card and its stylesheet travel together, and on their own terms: a December page
    // that puts snow on the viewport and never opens the card never asks for either of them.
    if (asked === 'hud' || hudMode()) {loadCard(null);}
    for (const name of FIELD_SCRIPTS) {injectScript(name, null);}
  }

  /**
   * Puts one script on the page.
   *
   * async=false is what keeps the load order: a script inserted at runtime is async by
   * default, and a field whose page side runs before the worker it drives is a field that
   * never starts.
   *
   * @param {string} name the file beside this loader
   * @param {Array<Object>|null} extra a second list to remember the element in, for the ones
   *        that have to come back off without the rest of the field
   * @returns {Object} the script element
   */
  function injectScript(name, extra) {
    const script = document.createElement('script');
    script.src = baseDir() + name;
    script.async = false;
    script.addEventListener('error', onLoadError);
    document.body.appendChild(script);
    injected.push(script);
    if (extra) {extra.push(script);}
    return script;
  }

  /**
   * Puts the card's module on the page, once, and runs what happens next when it has run.
   *
   * @param {Function|null} then to run once the card's module is on the page
   * @returns {void}
   */
  function loadCard(then) {
    injectSheet();
    if (hudLoaded()) {
      if (then) {then();}
      return;
    }
    const script = injectScript(HUD_SCRIPT, card);
    if (then) {script.addEventListener('load', then, {once: true});}
  }

  /**
   * Whether the card's module is on the page.
   *
   * @returns {boolean} true when hud.js has run
   */
  function hudLoaded() {
    return typeof window.SNOWHUD === 'object';
  }

  /**
   * Puts the card's stylesheet on the page, once.
   *
   * It is appended to the head rather than put beside the scripts so it lands after every
   * theme sheet and wins the ties on order as well as on specificity.
   *
   * @returns {void}
   */
  function injectSheet() {
    if (sheet) {return;}
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = baseDir() + SHEET;
    link.addEventListener('load', readySheet, {once: true});
    link.addEventListener('error', readySheet, {once: true});
    document.head.appendChild(link);
    sheet = link;
    injected.push(link);
    card.push(link);
  }

  /**
   * Says the sheet has landed, or refused to, and runs everything that was waiting on it.
   *
   * A sheet that will not load still counts: a card shown without its colours is worse
   * than a card shown late, and waiting on a fetch that failed would never open it at all.
   *
   * @returns {void}
   */
  function readySheet() {
    sheetReady = true;
    while (sheetWaiters.length) {sheetWaiters.shift()();}
  }

  /**
   * Runs something once the card's stylesheet is on the page — immediately if it already is.
   *
   * @param {Function} then what to run
   * @returns {void}
   */
  function whenSheet(then) {
    if (sheetReady) {then(); return;}
    sheetWaiters.push(then);
  }

  /**
   * A script that would not load leaves the field half-started, so the loader lets go of
   * the flag that was keeping it from trying again.
   *
   * @returns {void}
   */
  function onLoadError() {
    loading = false;
    pending = null;
  }

  /**
   * Whether the field's page side is on the page.
   *
   * @returns {boolean} true when snowflakes.js has run
   */
  function isLoaded() {
    return typeof window.snowGates === 'object';
  }

  /**
   * What the gesture that loaded the field asked for, taken once.
   *
   * The page side asks for the field as soon as it runs, so it has to be told whether the
   * load was a season, a query, or a reader typing 'hud' — the last of those also wants the
   * card open, and only the loader knows which case it is in.
   *
   * @returns {string|null} "hud", "snow", or null when the load was not a gesture
   */
  function takePending() {
    const asked = pending;
    pending = null;
    return asked;
  }

  /**
   * Brings the card onto a page that is already running the field, and opens it once both
   * of its files have landed.
   *
   * @returns {void}
   */
  function openHud() {
    storeHud(true);
    loadCard(() => {whenSheet(() => {
      if (typeof window.snowGates === 'object' && window.snowGates.openHud) {
        window.snowGates.openHud();
      }
    });});
  }

  /**
   * Takes the card off the page and remembers that the reader does not want it.
   *
   * This is what the card's own close button does, and it is deliberately not the field's
   * business: the snow may be running, and it carries on running. What goes is the card's
   * module and the card's stylesheet, and the card's element with them, so a page that wants
   * the card next time is a page that fetches both again rather than one that shows a card
   * whose colours never arrived.
   *
   * @returns {void}
   */
  function closeHud() {
    storeHud(false);
    const gates = typeof window.snowGates === 'object' ? window.snowGates : null;
    if (gates && typeof gates.dropHud === 'function') {gates.dropHud();}
    while (card.length) {
      const element = card.pop();
      const at = injected.indexOf(element);
      if (at !== -1) {injected.splice(at, 1);}
      if (element.parentNode) {element.parentNode.removeChild(element);}
    }
    if (sheet) {
      sheet = null;
      sheetReady = false;
      sheetWaiters.length = 0;
    }
    try {
      delete window.SNOWHUD;
    } catch (e) {
      window.SNOWHUD = undefined;
    }
  }

  /**
   * Takes the field's scripts back off the page.
   *
   * A script tag cannot be unrun, so this is the honest half of an unload: the page side
   * removes its own listeners and its canvas, the globals it published are dropped so the
   * code can be collected, and the elements go with them. What stays is what the browser
   * keeps anyway — the fetched file in the HTTP cache — which is the cost this loader is
   * trying to avoid paying before anyone asks.
   *
   * @returns {void}
   */
  function unload() {
    if (window.snowGates && typeof window.snowGates.shutdown === 'function') {
      window.snowGates.shutdown();
    }
    while (injected.length) {
      const element = injected.pop();
      if (element.parentNode) {element.parentNode.removeChild(element);}
    }
    card.length = 0;
    sheet = null;
    sheetReady = false;
    sheetWaiters.length = 0;
    for (const name of GLOBALS) {
      try {
        delete window[name];
      } catch (e) {
        window[name] = undefined;
      }
    }
    loading = false;
    pending = null;
    fieldIndex = 0;
    hudIndex = 0;
    watch();
  }

  /**
   * Watches the keystream for the two gestures.
   *
   * Installed only while the field is not on the page: once it is, the page side owns the
   * gestures, because switching the field off and opening the card need the worker and the
   * card to answer them.
   *
   * @returns {void}
   */
  function watch() {
    document.removeEventListener('keydown', onKey);
    document.addEventListener('keydown', onKey);
  }

  /**
   * Stops watching the keystream.
   * @returns {void}
   */
  function unwatch() {
    document.removeEventListener('keydown', onKey);
  }

  /**
   * One keystroke, matched against both gestures.
   *
   * Typing in a field is not a gesture: a console page is full of text inputs, and a reader
   * typing the word 'snow' into a filter box did not ask for weather.
   *
   * The keystream stays here whether or not the field is on the page. It is the loader's
   * business either way: before the load it is what decides to fetch anything, and after it
   * the gestures only ask the field to start or stop, which is the same decision one step
   * in. A page script that owned its own listener could not be taken off the page.
   *
   * @param {KeyboardEvent} event the keystroke
   * @returns {void}
   */
  function onKey(event) {
    if (event.ctrlKey && event.altKey && (event.key === 's' || event.key === 'S')) {
      event.preventDefault();
      ask('snow');
      return;
    }
    if (event.ctrlKey || event.altKey || event.metaKey || event.repeat) {return;}
    const target = event.target;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' ||
                   target.tagName === 'SELECT' || target.isContentEditable)) {return;}
    const key = typeof event.key === 'string' ? event.key.toLowerCase() : '';
    const now = Date.now();
    if (now - at > GESTURE_WINDOW_MS) {
      fieldIndex = 0;
      hudIndex = 0;
    }
    at = now;
    if (key === HUD_SEQUENCE.charAt(hudIndex)) {
      hudIndex++;
      if (hudIndex === HUD_SEQUENCE.length) {
        hudIndex = 0;
        ask('hud');
        return;
      }
    } else {
      hudIndex = key === HUD_SEQUENCE.charAt(0) ? 1 : 0;
    }
    if (key === FIELD_SEQUENCE.charAt(fieldIndex)) {
      fieldIndex++;
      if (fieldIndex === FIELD_SEQUENCE.length) {
        fieldIndex = 0;
        ask('snow');
      }
    } else {
      fieldIndex = 0;
    }
  }

  /**
   * Answers one gesture: fetch the field if it is not on the page, hand it to the field if
   * it is.
   *
   * @param {string} asked "snow" to toggle the field, "hud" to open the tuning card
   * @returns {void}
   */
  function ask(asked) {
    if (!isLoaded()) {
      // The gesture is the reader asking, so each one is remembered the way its own kind of
      // toggle is remembered: the next page load answers the same way.
      if (asked === 'snow') {storeMode('on');} else {storeHud(true);}
      load(asked);
      return;
    }
    if (asked === 'hud') {
      // Typing the gesture is the reader asking for the card, whether it is on the page
      // already or was shut and taken off with its own files.
      openHud();
      return;
    }
    // The field's own toggle remembers the choice; switching it off leaves nothing on the
    // page that needs these files, so they come back off and the keystream is watched again.
    if (window.snowGates.toggle) {window.snowGates.toggle();}
  }

  // The whole reason the effect is quiet for eleven months a year: nothing is fetched until
  // one of the conditions holds. The keystream is watched either way — before a load it is
  // what decides to fetch anything, and after one the same gestures toggle the field and
  // open the card, which is the same decision one step in. A card left open is a field
  // wanted too, since the card drives it, so either one brings the stack in.
  if (decide() === 'on' || hudMode()) {load(null);}
  watch();

  // The JS harness (tools/test/js/pure/snowGates.test.js) drives these directly, and
  // snowflakes.js reads the decision rules from here.
  window.snowBoot = Object.freeze({
    LS_KEY, HUD_KEY, SEASON_FIRST_MONTH, SEASON_LAST_MONTH, SEASON_LAST_DAY,
    GESTURE_WINDOW_MS, inSeason, modeFor, urlMode, hudRequested, storedMode, storeMode,
    hudMode, storedHud, storeHud, prefersReducedMotion, decide, load, loadCard, hudLoaded,
    whenSheet, openHud, closeHud, unload, isLoaded, takePending, watch, unwatch
  });
}());
