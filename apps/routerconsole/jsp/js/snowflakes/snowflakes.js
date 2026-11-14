/*
    Snow for the I2P+ router console.

    Effect, colours and drift constants: Snowflakes © 2024 Denis Seleznev
    (MIT, https://github.com/hcodes/snowflakes/). Worker transport, sprite
    atlas, offscreen rendering and the season gate for I2P+ by dr|z3d,
    AGPLv3 or later.
*/

/**
 * @module snowflakes
 * @description Page side of the router console's festive snow. It decides whether
 * snow should run at all, rasterizes the flake shapes the worker generates into
 * one sprite atlas, and hands the animation to a dedicated worker (snowman.js).
 * Where the browser supports OffscreenCanvas the worker paints the canvas itself
 * and the page runs no animation code per frame; where it does not, the worker
 * posts one packed Float32Array of draw transforms per frame and the page paints
 * them with no trigonometry of its own.
 *
 * Snow is decoration, so it is gated: it runs from 1 December to 6 January, and
 * not at all when the visitor asks for reduced motion. Ctrl+Alt+S, or typing the
 * word "snow" with no field focused, overrides the gate for this browser and
 * remembers the choice.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

(() => {
  'use strict';

  /** Worker holding the physics and, where it can, the painting. */
  const WORKER_URL = '/js/snowflakes/snowman.js';
  /**
   * Hand-drawn flake shapes, index 0 is flake1.svg — the fallback the worker's
   * type number indexes when it could not generate shapes of its own. Nothing is
   * fetched unless that happens.
   */
  const FLAKE_URLS = [
    '/js/snowflakes/flake1.svg',
    '/js/snowflakes/flake2.svg',
    '/js/snowflakes/flake3.svg',
    '/js/snowflakes/flake4.svg',
    '/js/snowflakes/flake5.svg',
    '/js/snowflakes/flake6.svg'
  ];
  /** localStorage key holding the visitor's own choice: "on" or "off". */
  const LS_KEY = 'snowMode';
  /** Flake colours per theme, inherited from the original. */
  const THEME_COLORS = {
    light: '#78c3f2',
    dark: '#5a9d68',
    classic: '#b0e0e6',
    midnight: '#9662ca'
  };
  /** Season bounds, inclusive: 1 December through 6 January. */
  const SEASON_FIRST_MONTH = 11;
  const SEASON_LAST_MONTH = 0;
  const SEASON_LAST_DAY = 6;
  /** Typed sequence that toggles snow, and how long it may take. */
  const HOTKEY_SEQUENCE = 'snow';
  const HOTKEY_WINDOW_MS = 2000;
  /** Resize work is batched; a canvas reallocation per drag event is waste. */
  const RESIZE_DEBOUNCE_MS = 150;
  /**
   * How long to wait for the worker to confirm it has resized before showing the
   * canvas anyway. The canvas stays hidden rather than showing a stretched frame,
   * so a lost ack costs a blink, not a wrong aspect ratio.
   */
  const VIEW_ACK_TIMEOUT_MS = 400;
  /** Bounds on the startup fetches so a stalled link cannot hang the effect. */
  const FETCH_TIMEOUT_MS = 4000;
  const IMAGE_TIMEOUT_MS = 3000;
  /**
   * Frame-rate bands the adaptive controller reacts to. Both are read against the
   * rate the worker's loop runs at, not the rate it paints at: a skipped paint is
   * cheap, so a loop that had fallen behind climbs back to the display's rate, and
   * counting paints instead would read that climb as spare capacity.
   */
  const FPS_LOW = 45;
  const FPS_HIGH = 57;
  /**
   * Paint ceiling and floor, in frames a second, and both absolute rather than
   * relative to the panel. The loop itself is capped only by the display's refresh
   * rate, because requestAnimationFrame fires at the panel: 60Hz on a laptop, 144Hz
   * on a gaming monitor. Painting at the panel rate is waste above 60 — a flake
   * drifts a third of a pixel between beats — and the divisor that buys the ceiling
   * has to be worked out from the measured rate, because two beats is 30fps on one
   * panel and 72 on another.
   */
  const TARGET_FPS = 60;
  const FLOOR_FPS = 30;
  /**
   * Floor for the flake count, deliberately below the worker's own minimum. The
   * worker's floor is what a viewport is worth at rest; this is how far beneath
   * that the controller may go once the machine has proved it cannot keep up.
   */
  const FLOOR_FLAKES = 6;
  /**
   * Hard clamp on the paint divisor, mirroring the worker's. The real bound is
   * {@link maxRate}, which keeps the paint above {@link FLOOR_FPS} whatever the
   * panel; this only stops a bad measurement asking for something absurd.
   */
  const RATE_MAX = 4;
  /**
   * Backing-store scale. The old full-viewport canvas ran at 1 and blurred on a
   * HiDPI screen; 1.5 is visibly sharper for a fraction of the pixel cost, and a
   * machine with two cores or two gigabytes is left at 1.
   */
  const DPR_CAP = 1.5;
  const DPR_CAP_WEAK = 1;

  /** Everything the page keeps about the running effect. */
  const state = {
    worker: null,
    canvas: null,
    ctx: null,
    atlas: null,
    layout: null,
    cell: 0,
    stride: 8,
    mode: 'physics',
    shapes: null,
    base: 0,
    count: 0,
    dpr: 1,
    /** Paint divisor in force, 1 or more. */
    rate: 1,
    /** Whether the first stats window has set the paint ceiling yet. */
    calibrated: false,
    /** The panel's own refresh rate, measured before any paint was thinned. */
    panel: 0,
    raf: 0,
    dirtyRects: null,
    dirty: 0,
    dirtyAll: true,
    low: 0,
    high: 0,
    seqIndex: 0,
    seqAt: 0,
    resizeTimer: null,
    viewTimer: null,
    starting: false,
    failed: false,
    retried: false
  };

  /**
   * Whether a date falls inside the snow season: all of December, and 1–6
   * January. The viewer's own calendar is what matters for a festive effect, so
   * this is the browser's local date, not the router's.
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
   * Resolves the gates into a decision. An explicit choice beats the calendar,
   * and a URL flag beats a stored choice so a single page can be shown with or
   * without snow without changing what the browser remembers.
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
   * The paint divisor that holds the draw at or below {@link TARGET_FPS} on a panel
   * running at the measured rate.
   *
   * Nothing else caps the frame rate: requestAnimationFrame fires at the panel, so a
   * 144Hz monitor drives 144 paints a second over flakes that travel a third of a
   * pixel between them. This is the one-shot calibration that stops spending those
   * extra beats on nothing.
   *
   * @param {number} fps the loop rate the worker measured, in frames per second
   * @returns {number} a divisor of at least 1
   */
  function rateFor(fps) {
    return Math.max(1, Math.round((fps | 0) / TARGET_FPS));
  }

  /**
   * The largest paint divisor that still draws at or above {@link FLOOR_FPS}.
   *
   * A divisor is relative to the panel, so a fixed divisor ceiling would mean 30
   * frames a second on one display and 72 on another. This converts the floor the
   * reader actually perceives into whatever divisor the panel can afford, and it is
   * what hands the ladder over to cutting flakes once thinning the paint can go no
   * further.
   *
   * It is worked out from the panel rate rather than the rate measured this window,
   * and that distinction matters: a loop measured at 30 is saturated, and thinning
   * its paint is exactly what lets it climb back to 60 while still drawing at 30.
   * Bounding the divisor by the saturated number would forbid the one step that
   * fixes it.
   *
   * @param {number} panel the panel's own refresh rate in frames per second, as
   *   measured before any paint was thinned
   * @returns {number} a divisor of at least 1, never above {@link RATE_MAX}
   */
  function maxRate(panel) {
    return Math.max(1, Math.min(RATE_MAX, Math.floor((panel | 0) / FLOOR_FPS)));
  }

  /**
   * Decides the next paint divisor and flake count from a measured frame rate.
   *
   * The two levers are ordered by how little they change what is on screen. Thinning
   * the paint goes first: the field keeps its crystals, its colours and its falling
   * speed, because the worker's fixed timestep still steps every beat and only the
   * redraw is skipped. Cutting flakes goes second, and that changes the look of the
   * snow outright.
   *
   * Two slow windows in a row must pass before either lever moves, and three fast
   * ones before anything is given back, so a single hiccup cannot make the snow
   * pulse.
   *
   * The divisor only comes down, never back up. Once the paint has been thinned the
   * loop climbs back to the panel's own rate, and there is nothing in that number to
   * say whether the thinning is still needed, so a recovery would be a blind
   * re-probe of a setting the machine already failed — and on the hardware this
   * ladder exists for, that probe is a stutter every ten seconds. The flake count
   * does recover, on the same three-window signal, up to the count the viewport
   * asked for.
   *
   * @param {number} count the current flake count
   * @param {number} base the count the viewport asked for, the ceiling
   * @param {number} panel the panel's refresh rate, from {@link maxRate}
   * @param {number} fps loop frames per second measured over the last window
   * @param {number} rate the paint divisor in force, 1 or more
   * @param {Object} streaks consecutive-window counters
   * @param {number} streaks.low consecutive windows below {@link FPS_LOW}
   * @param {number} streaks.high consecutive windows above {@link FPS_HIGH}
   * @returns {{count: number, rate: number, low: number, high: number,
   *   changed: boolean}} the settings to use and the streaks to carry into the next
   *   window
   */
  function qualityStep(count, base, panel, fps, rate, streaks) {
    let low = streaks.low | 0;
    let high = streaks.high | 0;
    const was = Math.max(1, rate | 0);
    let nextRate = was;
    let next = count;
    if (fps < FPS_LOW) {
      high = 0;
      low++;
      if (low >= 2) {
        // the paint first, and only the field once the paint cannot go lower without
        // dropping the reader under FLOOR_FPS
        if (nextRate < maxRate(panel)) {nextRate++;} else {next = Math.max(FLOOR_FLAKES, Math.round(count * 0.7));}
        low = 0;
      }
    } else if (fps > FPS_HIGH) {
      low = 0;
      high++;
      if (high >= 3) {
        next = Math.min(base, count + 2);
        high = 0;
      }
    } else {
      low = 0;
      high = 0;
    }
    return {count: next, rate: nextRate, low, high, changed: next !== count || nextRate !== was};
  }

  /**
   * Picks the backing-store scale, trading sharpness for pixels on machines that
   * report themselves small.
   *
   * @returns {number} a device pixel ratio of at least 1
   */
  function pickDpr() {
    const raw = Number(window.devicePixelRatio) || 1;
    const cores = Number(navigator.hardwareConcurrency) || 4;
    const memory = Number(navigator.deviceMemory) || 4;
    const cap = cores <= 2 || memory <= 2 ? DPR_CAP_WEAK : DPR_CAP;
    return Math.max(1, Math.min(raw, cap));
  }

  /**
   * Whether this browser asked for motion to be reduced.
   *
   * @returns {boolean} true when reduced motion is requested
   */
  function prefersReducedMotion() {
    if (typeof matchMedia !== 'function') {return false;}
    return matchMedia('(prefers-reduced-motion: reduce)').matches === true;
  }

  /**
   * Reads the visitor's stored choice, ignoring anything that is not one of the
   * two values this script writes.
   *
   * @returns {string|null} "on", "off", or null when nothing is stored
   */
  function storedMode() {
    let value = null;
    try {
      value = localStorage.getItem(LS_KEY);
    } catch {
      return null;
    }
    return value === 'on' || value === 'off' ? value : null;
  }

  /**
   * Reads a one-page override from the query string: ?snow=1, ?snow=on, ?snow=0
   * or ?snow=off.
   *
   * @returns {string|null} "on", "off", or null when the URL says nothing
   */
  function urlMode() {
    let query = '';
    try {
      query = window.location.search || '';
    } catch {
      return null;
    }
    if (query.indexOf('snow=') === -1) {return null;}
    let value = null;
    try {
      value = new URLSearchParams(query).get('snow');
    } catch {
      return null;
    }
    if (value === '1' || value === 'on') {return 'on';}
    return value === '0' || value === 'off' ? 'off' : null;
  }

  /**
   * Remembers the visitor's choice. Storage is not guaranteed — private browsing
   * throws on write — and a failure only costs the memory, not the effect.
   *
   * @param {string} value "on" or "off"
   * @returns {void}
   */
  function storeMode(value) {
    try {
      localStorage.setItem(LS_KEY, value);
    } catch {
      /* nothing to remember it in */
    }
  }

  /**
   * The viewport the canvas has to cover, or null when the page reports nothing
   * usable (which is what a headless or pre-layout environment does).
   *
   * @returns {{width: number, height: number}|null} viewport size in CSS pixels
   */
  function viewportSize() {
    const width = Math.round(Number(window.innerWidth) || 0);
    const height = Math.round(Number(window.innerHeight) || 0);
    return width > 0 && height > 0 ? {width, height} : null;
  }

  /**
   * The flake colour for the page's theme.
   *
   * @returns {string} a #rrggbb colour
   */
  function themeColor() {
    const theme = typeof window.theme === 'string' ? window.theme : 'light';
    return THEME_COLORS[theme] || '#87ceeb';
  }

  /**
   * Fetches one flake shape as text, bounded by a timeout so a stalled request
   * cannot leave the effect waiting forever.
   *
   * @param {string} url the shape to fetch
   * @returns {Promise<string|null>} the SVG text, or null on any failure
   */
  function fetchText(url) {
    return new Promise(resolve => {
      let settled = false;
      const finish = value => {
        if (settled) {return;}
        settled = true;
        clearTimeout(timer);
        resolve(value);
      };
      const timer = setTimeout(() => finish(null), FETCH_TIMEOUT_MS);
      if (typeof fetch !== 'function') {finish(null);return;}
      fetch(url).then(response => {
        if (!response.ok) {finish(null);return;}
        response.text().then(text => finish(text || null), () => finish(null));
      }, () => finish(null));
    });
  }

  /**
   * Fetches the six hand-drawn flake shapes. This is the fallback: the worker
   * normally generates its own shapes and sends them with its ready message, and
   * nothing is fetched at all in that case. The page keeps the markup because it
   * is the page that rasterizes the atlas in the mode where the worker only does
   * physics.
   *
   * @returns {Promise<string[]|null>} the shapes in flake-type order, or null
   *   when none of them could be read
   */
  async function loadShapes() {
    const shapes = await Promise.all(FLAKE_URLS.map(fetchText));
    const usable = shapes.filter(text => typeof text === 'string' && text.length > 0);
    return usable.length > 0 ? shapes.map(text => text || '') : null;
  }

  /**
   * Decodes one colored flake shape into an image.
   *
   * @param {string} svgText the shape markup with :color: placeholders
   * @param {string} color the rgb() colour to substitute
   * @returns {Promise<Image|null>} the decoded image, or null on failure
   */
  function loadShapeImage(svgText, color) {
    return new Promise(resolve => {
      let settled = false;
      let objectUrl = null;
      const finish = value => {
        if (settled) {return;}
        settled = true;
        clearTimeout(timer);
        if (objectUrl && typeof URL.revokeObjectURL === 'function') {URL.revokeObjectURL(objectUrl);}
        resolve(value);
      };
      const timer = setTimeout(() => finish(null), IMAGE_TIMEOUT_MS);
      if (typeof Blob !== 'function' || !URL || typeof URL.createObjectURL !== 'function') {
        finish(null);
        return;
      }
      const image = new Image();
      image.onload = () => finish(image);
      image.onerror = () => finish(null);
      objectUrl = URL.createObjectURL(new Blob([svgText.replace(/:color:/g, color)],
                                               {type: 'image/svg+xml'}));
      image.src = objectUrl;
    });
  }

  /**
   * Rasterizes every flake slot into one sprite atlas, so a frame is a draw of
   * cached pixels rather than a fresh SVG rasterization. The original redrew each
   * flake's SVG at a size that changed every frame, which re-rasterized the shape
   * on every frame of the animation.
   *
   * Cells are rasterized well above the size a flake is ever drawn at, so the
   * shrink as a flake ages is a downscale of a bitmap, not a re-render.
   *
   * @param {Array<{type: number, color: string}>} cells one entry per flake slot
   * @param {string[]} shapes shape markup, {@link frames} documents per slot,
   *   slot-major, 1-based by {@link cells}
   * @param {number} cell edge length of one atlas cell in pixels
   * @param {Object} layout the atlas grid
   * @param {number} [frames] shape documents per slot
   * @returns {Promise<HTMLCanvasElement|null>} the atlas, or null when no cell
   *   could be drawn at all
   */
  async function buildAtlas(cells, shapes, cell, layout, frames) {
    const atlas = document.createElement('canvas');
    atlas.width = layout.width;
    atlas.height = layout.height;
    const atlasCtx = atlas.getContext('2d');
    if (!atlasCtx) {return null;}
    const per = Math.max(1, frames || 1);
    const images = await Promise.all(shapes.map((svg, i) =>
      loadShapeImage(svg, cells[Math.floor(i / per) % cells.length].color)));
    let drawn = 0;
    for (let i = 0; i < images.length; i++) {
      const image = images[i];
      if (!image) {continue;}
      atlasCtx.drawImage(image, (i % layout.cols) * cell, Math.floor(i / layout.cols) * cell, cell, cell);
      drawn++;
    }
    return drawn > 0 ? atlas : null;
  }

  /**
   * Creates the canvas the snow is painted on. It sits above the page and takes
   * no pointer events, exactly as the original did.
   *
   * @returns {HTMLCanvasElement|null} the canvas, or null if the page has no body
   */
  function makeCanvas() {
    if (!document.body || typeof document.createElement !== 'function') {return null;}
    const canvas = document.createElement('canvas');
    canvas.id = 'snowflakeCanvas';
    canvas.style.cssText = 'position:fixed;top:0;left:0;z-index:999999999;' +
                           'pointer-events:none;transition:opacity 120ms ease-out;';
    document.body.appendChild(canvas);
    return canvas;
  }

  /**
   * Sizes the page-side canvas to the viewport at the chosen pixel ratio.
   *
   * @param {{width: number, height: number}} viewport size in CSS pixels
   * @returns {void}
   */
  function sizeCanvas(viewport) {
    if (!state.canvas) {return;}
    state.canvas.width = Math.max(1, Math.round(viewport.width * state.dpr));
    state.canvas.height = Math.max(1, Math.round(viewport.height * state.dpr));
  }

  /**
   * Pins the canvas's CSS box to the same CSS pixels its backing store is sized
   * from. A percentage-sized canvas over a viewport-sized backing store stretches
   * whenever the two disagree — mid-resize, or when a scrollbar comes and goes —
   * and a stretch is what makes a flake read as an ellipse. A fixed-position
   * element larger than the viewport adds no scrollbars, so being momentarily
   * oversized is harmless; being momentarily squashed is not.
   *
   * @param {{width: number, height: number}} viewport size in CSS pixels
   * @returns {void}
   */
  function sizeCanvasCss(viewport) {
    if (!state.canvas) {return;}
    state.canvas.style.width = viewport.width + 'px';
    state.canvas.style.height = viewport.height + 'px';
  }

  /**
   * Whether this browser can hand a canvas to a worker and get an image the
   * worker can be given. Probed, not assumed.
   *
   * @returns {boolean} true when offscreen painting is worth trying
   */
  function offscreenCapable() {
    if (typeof OffscreenCanvas !== 'function') {return false;}
    if (typeof HTMLCanvasElement !== 'function' ||
        typeof HTMLCanvasElement.prototype.transferControlToOffscreen !== 'function') {return false;}
    return typeof createImageBitmap === 'function';
  }

  /**
   * Paints one frame the worker measured: clear what the previous frame covered,
   * then draw each flake with the transform the worker already baked. No trig, no
   * per-flake state, and no full-viewport clear — the rectangles a frame actually
   * painted are a few tens of thousands of pixels, where the viewport is millions.
   *
   * @param {Float32Array} buf packed draw transforms
   * @param {number} count flakes packed into buf
   * @returns {void}
   */
  function paintFrame(buf, count) {
    const ctx = state.ctx;
    if (!ctx || !state.atlas || !buf) {return;}
    const rects = state.dirtyRects;
    if (state.dirtyAll) {
      ctx.clearRect(0, 0, state.canvas.width, state.canvas.height);
      state.dirtyAll = false;
    } else {
      for (let i = 0; i < state.dirty; i++) {
        ctx.clearRect(rects[i * 4], rects[i * 4 + 1], rects[i * 4 + 2], rects[i * 4 + 3]);
      }
    }
    const cols = state.layout.cols;
    const cell = state.cell;
    for (let i = 0; i < count; i++) {
      const at = i * state.stride;
      const a = buf[at + 2];
      const b = buf[at + 3];
      const slot = buf[at + 7] | 0;
      ctx.setTransform(a, b, buf[at + 4], buf[at + 5], buf[at], buf[at + 1]);
      ctx.globalAlpha = buf[at + 6];
      ctx.drawImage(state.atlas, (slot % cols) * cell, Math.floor(slot / cols) * cell, cell, cell,
                    -1, -1, 2, 2);
      const half = Math.abs(a) + Math.abs(b);
      rects[i * 4] = buf[at] - half;
      rects[i * 4 + 1] = buf[at + 1] - half;
      rects[i * 4 + 2] = half * 2;
      rects[i * 4 + 3] = half * 2;
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    state.dirty = count;
  }

  /**
   * Asks the worker for one frame. Only needed in the mode where the page paints;
   * with an OffscreenCanvas the worker drives itself and the page is idle.
   *
   * @param {number} t the frame timestamp from requestAnimationFrame
   * @returns {void}
   */
  function tick(t) {
    if (!state.worker) {return;}
    state.raf = requestAnimationFrame(tick);
    state.worker.postMessage({type: 'tick', t});
  }

  /**
   * Starts the page-side frame pump.
   *
   * @returns {void}
   */
  function startPump() {
    if (state.raf || typeof requestAnimationFrame !== 'function') {return;}
    state.raf = requestAnimationFrame(tick);
  }

  /**
   * Stops the page-side frame pump.
   *
   * @returns {void}
   */
  function stopPump() {
    if (state.raf && typeof cancelAnimationFrame === 'function') {cancelAnimationFrame(state.raf);}
    state.raf = 0;
  }

  /**
   * Applies one measured frame rate: keep, cut, or restore flakes.
   *
   * @param {number} fps the rate the worker measured over its last window
   * @returns {void}
   */
  function applyStats(fps) {
    if (!(fps > 0)) {return;}
    // One-shot: the first window is the only measurement of the panel at full rate,
    // so it is where the paint ceiling gets set. After that the divisor only moves
    // down, and re-deriving it would be a re-probe of a setting already rejected.
    if (!state.calibrated) {
      state.calibrated = true;
      // The first window is the only one taken before any paint was thinned, so it
      // is the best estimate of what the panel itself runs at.
      state.panel = fps;
      setRate(rateFor(fps));
    }
    const next = qualityStep(state.count, state.base, state.panel, fps, state.rate, state);
    state.low = next.low;
    state.high = next.high;
    if (!state.worker) {return;}
    if (next.rate !== state.rate) {setRate(next.rate);}
    if (next.count !== state.count) {
      state.count = next.count;
      state.dirtyAll = true;
      state.worker.postMessage({type: 'quality', count: next.count});
    }
  }

  /**
   * Publishes a new paint divisor to the worker and to the page's own state.
   *
   * @param {number} rate beats to skip between paints, 1 or more
   * @returns {void}
   */
  function setRate(rate) {
    const want = Math.max(1, rate | 0);
    if (want === state.rate) {return;}
    state.rate = want;
    if (state.worker) {state.worker.postMessage({type: 'rate', rate: want});}
  }

  /**
   * Gives up on snow for this page load after a worker or atlas failure, leaving
   * the page exactly as it was. A decoration is not worth a retry loop.
   *
   * @returns {void}
   */
  function giveUp() {
    state.failed = true;
    stop();
  }

  /**
   * Handles one message from the worker.
   *
   * @param {MessageEvent} event the message
   * @returns {void}
   */
  async function onMessage(event) {
    const msg = event.data || {};
    if (msg.type === 'frame') {
      paintFrame(msg.buf, msg.count);
      return;
    }
    if (msg.type === 'stats') {
      applyStats(msg.fps);
      return;
    }
    if (msg.type === 'viewed') {
      // The worker has resized its canvas to the new viewport: the backing store
      // and the CSS box agree again, so the flakes are the right shape.
      if (state.viewTimer) {
        clearTimeout(state.viewTimer);
        state.viewTimer = null;
      }
      const viewport = viewportSize();
      if (viewport) {sizeCanvasCss(viewport);}
      showCanvas(true);
      return;
    }
    if (msg.type === 'ready') {
      await onReady(msg);
      return;
    }
  }

  /**
   * Second half of the handshake: rasterize the atlas the worker asked for, then
   * release it into the mode both sides agreed on.
   *
   * @param {Object} msg the worker's ready message
   * @returns {Promise<void>} resolves once the atlas has been handed over
   */
  async function onReady(msg) {
    if (!state.worker) {return;}
    state.base = msg.count;
    state.count = msg.count;
    state.cell = msg.cell;
    state.stride = msg.stride;
    state.layout = msg.layout;
    state.dirtyRects = new Float32Array(Math.max(1, msg.count) * 4);
    state.dirty = 0;
    state.dirtyAll = true;
    // The worker generates its own shapes and sends them in the ready message, so
    // the normal path fetches nothing at all. The hand-drawn files are the
    // fallback for a worker that sent none, and they are fetched only then.
    if (!Array.isArray(msg.shapes) || msg.shapes.length === 0) {
      if (!state.shapes) {state.shapes = await loadShapes();}
    }
    if (!state.worker) {return;}
    // In offscreen mode the worker has already rasterized its own atlas from the
    // markup it generated, so the page decodes nothing at all — with a frame strip
    // per slot there are eight times the cells there used to be, and all of that
    // decode used to land on the main thread. The page only rasterizes in the
    // physics-only mode, where the page is the one painting.
    if (msg.raster) {
      state.atlas = null;
      return;
    }
    const shapes = Array.isArray(msg.shapes) && msg.shapes.length > 0 ? msg.shapes : state.shapes;
    if (!shapes || shapes.length === 0) {
      giveUp();
      return;
    }
    const atlas = await buildAtlas(msg.cells, shapes, msg.cell, msg.layout, msg.frames);
    if (!atlas || !state.worker) {
      giveUp();
      return;
    }
    state.atlas = atlas;
    if (msg.offscreen && state.mode === 'offscreen') {
      try {
        const bitmap = await createImageBitmap(atlas);
        if (state.worker) {state.worker.postMessage({type: 'atlas', bitmap}, [bitmap]);}
      } catch {
        restartInPhysicsMode();
      }
      return;
    }
    if (state.worker) {state.worker.postMessage({type: 'atlas'});}
    if (state.mode === 'physics') {startPump();}
  }

  /**
   * Falls back to the physics-only mode after the offscreen path turned out to be
   * unavailable in the worker. A transferred canvas cannot be painted by the page,
   * so the canvas is replaced rather than reused.
   *
   * @returns {void}
   */
  function restartInPhysicsMode() {
    if (state.retried || !state.worker) {return;}
    state.retried = true;
    if (state.canvas && state.canvas.parentNode) {state.canvas.parentNode.removeChild(state.canvas);}
    const viewport = viewportSize();
    const canvas = makeCanvas();
    if (!viewport || !canvas) {giveUp();return;}
    state.canvas = canvas;
    state.mode = 'physics';
    state.ctx = canvas.getContext('2d');
    if (!state.ctx) {giveUp();return;}
    sizeCanvasCss(viewport);
    sizeCanvas(viewport);
    state.atlas = null;
    state.worker.postMessage({
      type: 'init',
      width: viewport.width,
      height: viewport.height,
      dpr: state.dpr,
      rate: state.rate,
      count: state.base,
      baseColor: themeColor(),
      seed: Date.now() & 0x7fffffff
    });
  }

  /**
   * Starts the effect: fetch the shapes, create the canvas, and hand the whole
   * animation to the worker.
   *
   * @returns {Promise<void>} resolves once the worker has been started
   */
  async function start() {
    if (state.failed || state.starting || state.worker || state.canvas) {return;}
    if (typeof Worker !== 'function') {return;}
    const viewport = viewportSize();
    if (!viewport) {return;}
    state.starting = true;
    try {
      state.dpr = pickDpr();
      if (state.worker || state.canvas) {return;}
      const canvas = makeCanvas();
      if (!canvas) {return;}
      state.canvas = canvas;
      sizeCanvasCss(viewport);
      let offscreen = null;
      state.mode = 'physics';
      if (offscreenCapable()) {
        try {
          offscreen = canvas.transferControlToOffscreen();
          state.mode = 'offscreen';
        } catch {
          state.mode = 'physics';
        }
      }
      if (state.mode === 'physics') {
        state.ctx = canvas.getContext('2d');
        if (!state.ctx) {giveUp();return;}
        sizeCanvasCss(viewport);
        sizeCanvas(viewport);
      }
      const worker = new Worker(WORKER_URL);
      worker.addEventListener('message', onMessage);
      worker.addEventListener('error', giveUp);
      state.worker = worker;
      worker.postMessage({
        type: 'init',
        width: viewport.width,
        height: viewport.height,
        dpr: state.dpr,
        rate: state.rate,
        baseColor: themeColor(),
        seed: Date.now() & 0x7fffffff,
        canvas: offscreen
      }, offscreen ? [offscreen] : []);
    } finally {
      state.starting = false;
    }
  }

  /**
   * Stops the effect and releases everything it held: worker, canvas, frame pump
   * and pending resize.
   *
   * @returns {void}
   */
  function stop() {
    if (state.resizeTimer) {
      clearTimeout(state.resizeTimer);
      state.resizeTimer = null;
    }
    if (state.viewTimer) {
      clearTimeout(state.viewTimer);
      state.viewTimer = null;
    }
    stopPump();
    if (state.worker) {
      try {
        state.worker.postMessage({type: 'stop'});
      } catch {
        /* the worker may already be gone */
      }
      try {
        state.worker.terminate();
      } catch {
        /* already terminated */
      }
    }
    if (state.canvas && state.canvas.parentNode) {state.canvas.parentNode.removeChild(state.canvas);}
    state.worker = null;
    state.canvas = null;
    state.ctx = null;
    state.atlas = null;
    state.layout = null;
    // The flake markup stays cached for the page's life: it is a few kilobytes of
    // text, and dropping it would make a hotkey toggle refetch and re-decode all
    // six shapes before snow could come back.
    state.dirtyRects = null;
    state.dirty = 0;
    state.dirtyAll = true;
    state.base = 0;
    state.count = 0;
    state.low = 0;
    state.high = 0;
    // A restart re-measures the panel, so the ceiling is worked out again rather
    // than carried over from a window that may have been a different monitor.
    state.rate = 1;
    state.calibrated = false;
    state.panel = 0;
  }

  /**
   * Whether snow is running right now.
   *
   * @returns {boolean} true while the worker is alive
   */
  function isRunning() {return !!state.worker;}

  /**
   * What is running, for the browser console and for a test page: which of the
   * two modes the worker was started in, how many flakes it is painting, and the
   * pixel ratio it is painting at.
   *
   * @returns {{running: boolean, mode: string, count: number, base: number,
   *   dpr: number}} the current state
   */
  function status() {
    return {running: isRunning(), mode: state.mode, count: state.count, base: state.base,
            dpr: state.dpr, rate: state.rate, panel: Math.round(state.panel)};
  }

  /**
   * Toggles snow and remembers the choice, which is what the hotkey does.
   *
   * @returns {void}
   */
  function toggle() {
    if (isRunning()) {
      storeMode('off');
      stop();
      return;
    }
    storeMode('on');
    // A previous failure is not a permanent verdict for the whole page load: the
    // visitor asking for snow again is a reasonable reason to try once more.
    state.failed = false;
    start();
  }

  /**
   * Pauses the worker while the tab is hidden and resumes it when the tab is
   * seen again. Pausing rather than tearing down means returning to the tab does
   * not refetch the shapes or rebuild the atlas.
   *
   * @returns {void}
   */
  function onVisibility() {
    const visible = document.visibilityState !== 'hidden';
    if (state.worker) {state.worker.postMessage({type: 'visible', visible});}
    if (!visible) {
      stopPump();
    } else if (state.mode === 'physics' && state.atlas) {
      state.dirtyAll = true;
      startPump();
    }
  }

  /**
   * Hides or shows the canvas. A resize is the one place this matters: the canvas
   * is styled to the viewport, so between the first resize event and the
   * debounced reallocation of the backing store the old pixels are stretched to
   * the new box and the flakes read as squashed or elongated. They are hidden for
   * that window and faded back once the new size is genuinely in place.
   *
   * @param {boolean} visible whether the canvas should be seen
   * @returns {void}
   */
  function showCanvas(visible) {
    if (state.canvas) {state.canvas.style.opacity = visible ? "1" : "0";}
  }

  /**
   * Re-points the worker at a new viewport, batched: resizing the canvas
   * reallocates its whole backing store, so doing it on every drag event costs
   * more than the animation does. The canvas is faded out for the duration and
   * back in when the worker confirms it has resized, with a timer standing in
   * should that confirmation never arrive.
   *
   * @returns {void}
   */
  function onResize() {
    if (!state.worker) {return;}
    if (!state.resizeTimer) {showCanvas(false);}
    if (state.resizeTimer) {clearTimeout(state.resizeTimer);}
    state.resizeTimer = setTimeout(() => {
      state.resizeTimer = null;
      const viewport = viewportSize();
      if (!viewport || !state.worker) {showCanvas(true);return;}
      if (state.mode === 'physics') {sizeCanvas(viewport);}
      state.dirtyAll = true;
      state.worker.postMessage({type: 'view', width: viewport.width, height: viewport.height,
                                dpr: state.dpr});
      if (state.viewTimer) {clearTimeout(state.viewTimer);}
      state.viewTimer = setTimeout(() => {state.viewTimer = null; showCanvas(true);}, VIEW_ACK_TIMEOUT_MS);
    }, RESIZE_DEBOUNCE_MS);
  }

  /**
   * Installs the override keys: Ctrl+Alt+S, or the word "snow" typed with no
   * field focused. Installed whatever the calendar says, because the override is
   * the only way to see snow outside the season.
   *
   * @returns {void}
   */
  function installHotkeys() {
    document.addEventListener('keydown', event => {
      if (event.ctrlKey && event.altKey && (event.key === 's' || event.key === 'S')) {
        event.preventDefault();
        toggle();
        return;
      }
      if (event.ctrlKey || event.altKey || event.metaKey || event.repeat) {return;}
      const target = event.target;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' ||
                     target.tagName === 'SELECT' || target.isContentEditable)) {return;}
      const key = typeof event.key === 'string' ? event.key.toLowerCase() : '';
      const now = Date.now();
      if (now - state.seqAt > HOTKEY_WINDOW_MS) {state.seqIndex = 0;}
      state.seqAt = now;
      if (key === HOTKEY_SEQUENCE.charAt(state.seqIndex)) {
        state.seqIndex++;
        if (state.seqIndex === HOTKEY_SEQUENCE.length) {
          state.seqIndex = 0;
          toggle();
        }
      } else {
        state.seqIndex = 0;
      }
    });
  }

  /**
   * Decides whether snow runs on this page load and starts it if so.
   *
   * @returns {string} the mode that was chosen, for the caller's own use
   */
  function boot() {
    if (!document.body) {
      document.addEventListener('DOMContentLoaded', boot, {once: true});
      return 'waiting';
    }
    installHotkeys();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('resize', onResize);
    const mode = modeFor({url: urlMode(), stored: storedMode(),
                          reduced: prefersReducedMotion(), season: inSeason(new Date())});
    if (mode === 'on') {start();}
    return mode;
  }

  // The JS harness (tools/test/js/pure/snowGates.test.js) drives these directly,
  // and they are the whole reason the effect is quiet for eleven months a year.
  window.snowGates = Object.freeze({
    inSeason, modeFor, qualityStep, rateFor, maxRate, pickDpr, urlMode, storedMode, themeColor,
    status, start, stop, toggle, isRunning
  });

  boot();
})();
