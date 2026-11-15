/**
 * @module hud
 * @description The snow field's review and tuning card: hidden until it is asked for by
 * typing hud anywhere on the page, and holding the field's controls — how many flakes,
 * how often their crystals are replaced, and which of the crystal habits are in play at
 * all, each shown as a thumbnail of the crystal itself. Nothing about the shapes is drawn
 * out on the page: the viewport stays the field and nothing else. Everything the reviewer
 * touches is kept in localStorage, so a half-tuned field survives a reload.
 *
 * The card talks to the worker only through the page's own message channel, which is what
 * makes it usable both here and from a harness that drives the worker by hand.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  /** localStorage key the panel's settings are kept under. */
  const STORE = "i2p.snow.panel";
  /** Typed gesture that shows and hides the panel. */
  const GESTURE = "hud";
  /** Thumbnail edge length in CSS pixels. */
  const THUMB = 56;
  /**
   * How many elements the context-colour sample reads.
   *
   * The console's pages run to a few hundred elements, so this is a ceiling on a page
   * that has grown long, not a limit anyone reaches: getComputedStyle is the expensive
   * part of the sample and it is a synchronous layout per element.
   */
  const SAMPLE_NODES = 600;

  /**
   * Reads the saved settings.
   * @returns {Object} what was stored, or an empty object when nothing was
   */
  function load() {
    try {return JSON.parse(self.localStorage.getItem(STORE)) || {};} catch (e) {return {};}
  }

  /**
   * Attaches the panel to the page.
   *
   * @param {Object} opts the wiring
   * @param {Element} opts.root the element the panel renders into
   * @param {function(Object): void} opts.send posts one message to the snow worker
   * @param {string} [opts.baseColor] the colour the page paints snow in, as #rrggbb
   * @returns {Object} the handle the page uses: prefs, applyTo and stats
   */
  function attach(opts) {
    const H = self.SNOWMAN;
    const root = opts.root;
    const send = opts.send;
    if (!H || !root) {
      return {prefs: {}, applyTo: () => {}, stats: () => {}};
    }
    const saved = load();
    // A query override, so a reviewer can open the panel from a link or a screenshot
    // run without having typed the gesture first.
    const params = new URLSearchParams(self.location.search);
    const prefs = {
      seed: Number(params.get("seed")) || Number(saved.seed) || 20261115,
      // Not `Number(saved.count) || MAX`: zero is a count the reader may have set.
      count: params.has("count") ? Number(params.get("count"))
                                 : (Number.isFinite(Number(saved.count)) ? Number(saved.count)
                                                                         : H.MAX_FLAKES),
      refresh: params.has("refresh") ? Number(params.get("refresh"))
                                     : (saved.refresh === undefined ? 30 : Number(saved.refresh)),
      hue: params.has("hue") ? Number(params.get("hue")) : (Number(saved.hue) || 0),
      variation: params.has("variation") ? Number(params.get("variation"))
                                         : (saved.variation === undefined ? 15 : Number(saved.variation)),
      cycle: params.has("cycle") ? params.get("cycle") === "1" : !!saved.cycle,
      gravity: params.has("gravity") ? Number(params.get("gravity"))
                                     : (Number.isFinite(Number(saved.gravity)) ? Number(saved.gravity) : 100),
      gravityVar: params.has("gravityVar") ? Number(params.get("gravityVar"))
                                           : (saved.gravityVar === undefined ? H.DEFAULT_GRAVITY_VAR * 100
                                                                            : Number(saved.gravityVar)),
      spin: params.has("spin") ? Number(params.get("spin"))
                               : (Number.isFinite(Number(saved.spin)) ? Number(saved.spin) : 100),
      spinVar: params.has("spinVar") ? Number(params.get("spinVar"))
                                     : (saved.spinVar === undefined ? H.DEFAULT_SPIN_VAR * 100
                                                                    : Number(saved.spinVar)),
      size: params.has("size") ? Number(params.get("size"))
                               : (Number.isFinite(Number(saved.size)) ? Number(saved.size)
                                                                      : H.DEFAULT_SIZE * 100),
      sizeVar: params.has("sizeVar") ? Number(params.get("sizeVar"))
                                     : (saved.sizeVar === undefined ? H.DEFAULT_SIZE_VAR * 100
                                                                    : Number(saved.sizeVar)),
      // The hue the reader set themselves, kept apart from the hue the page picks, so
      // switching context colours off has something to hand back even after a reload.
      // Null means the page is in charge and no value of the reader's own is stored.
      hueManual: params.has("hue") ? Number(params.get("hue"))
                                   : (Number.isFinite(Number(saved.hueManual)) ? Number(saved.hueManual) : null),
      context: params.has("context") ? params.get("context") === "1" : !!saved.context,
      paused: !!saved.paused,
      // Whether the card is on screen. The loader's own stored key is what decides whether
      // this module is fetched at all, so a card that got this far starts open: the only
      // thing that shuts it is its own close button, and that hands the decision back to the
      // loader, which takes this module and its stylesheet off the page with it.
      open: params.has("hud") ? params.get("hud") === "1" : true,
      enabled: saved.enabled && typeof saved.enabled === "object" ? saved.enabled : {}
    };

    // Habits the worker marks as off by default — the figure habit, which is a snowman
    // and not a crystal — start out of the field unless the reader has already put one
    // in it.
    for (const habit of H.HABITS) {
      if (habit[8] === "off" && prefs.enabled[habit[0]] === undefined) {prefs.enabled[habit[0]] = false;}
    }

    /**
     * The variant map the card goes back to: every habit in play except the ones the
     * worker marks as off by default.
     *
     * An empty map is not the default. The worker reads a missing key as "in play", so a
     * reset that cleared the map would put the bonus habit — the snowman — back on a field
     * that had never shown one.
     *
     * @returns {Object} habit name to boolean
     */
    function defaultEnabled() {
      const on = {};
      for (const habit of H.HABITS) {
        if (habit[8] === "off") {on[habit[0]] = false;}
      }
      return on;
    }

    /** @returns {void} */
    function save() {
      try {self.localStorage.setItem(STORE, JSON.stringify(prefs));} catch (e) {}
    }

    /**
     * Keeps a slider's filled track in step with its value. The track is a gradient with
     * one stop, and the stop is this number, so the filled part ends exactly under the
     * thumb rather than approximately where it looks like it should.
     *
     * @param {HTMLInputElement} range the slider
     * @returns {void}
     */
    function paint(range) {
      const span = Number(range.max) - Number(range.min);
      const pct = span > 0 ? ((Number(range.value) - Number(range.min)) / span) * 100 : 0;
      range.style.setProperty("--pct", pct.toFixed(1) + "%");
    }

    /** @returns {boolean} whether a variant is in play @param {string} name the habit */
    function on(name) {return prefs.enabled[name] !== false;}

    root.innerHTML =
      '<div class="hud-head"><span class="hud-title">snow field</span>' +
      '<span class="hud-stats" id="hud-stats"></span></div>' +
      '<button class="hud-x" type="button" title="close (or type ' + GESTURE + ')">&times;</button>' +
      '<div class="hud-row"><span class="hud-k">flakes</span>' +
      '<input type="range" id="hud-count" min="0" max="' + H.TUNING_MAX_FLAKES + '" step="1">' +
      '<span class="hud-v" id="hud-count-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">new crystals</span>' +
      '<input type="range" id="hud-refresh" min="0" max="100" step="5">' +
      '<span class="hud-v" id="hud-refresh-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">hue</span>' +
      '<input type="range" id="hud-hue" min="0" max="360" step="5">' +
      '<span class="hud-v" id="hud-hue-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">hue variation</span>' +
      '<input type="range" id="hud-variation" min="0" max="100" step="5">' +
      '<span class="hud-v" id="hud-variation-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">gravity</span>' +
      '<input type="range" id="hud-gravity" min="20" max="300" step="5">' +
      '<span class="hud-v" id="hud-gravity-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">gravity var</span>' +
      '<input type="range" id="hud-gravity-var" min="0" max="100" step="5">' +
      '<span class="hud-v" id="hud-gravity-var-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">spin</span>' +
      '<input type="range" id="hud-spin" min="0" max="300" step="5">' +
      '<span class="hud-v" id="hud-spin-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">spin var</span>' +
      '<input type="range" id="hud-spin-var" min="0" max="' + H.SPIN_VAR_MAX * 100 + '" step="5">' +
      '<span class="hud-v" id="hud-spin-var-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">size</span>' +
      '<input type="range" id="hud-size" min="' + H.SIZE_MIN * 100 + '" max="' + H.SIZE_MAX * 100 + '" step="5">' +
      '<span class="hud-v" id="hud-size-v"></span></div>' +
      '<div class="hud-row"><span class="hud-k">size var</span>' +
      '<input type="range" id="hud-size-var" min="0" max="100" step="5">' +
      '<span class="hud-v" id="hud-size-var-v"></span></div>' +
      '<div class="hud-bools"><label><input type="checkbox" id="hud-context" title="hue from the page; the hue slider turns this off">' +
      'context colors</label>' +
      '<label class="hud-right"><input type="checkbox" id="hud-cycle">color cycle</label></div>' +
      '<div class="hud-vars" id="hud-vars"></div>' +
      '<div class="hud-foot"><button class="hud-act" type="button" id="hud-reset">' +
      'reset</button>' +
      '<button class="hud-act hud-left" type="button" id="hud-pause" aria-pressed="false">pause</button>' +
      '<button class="hud-act" type="button" id="hud-random">randomize</button>' +
      '<button class="hud-act" type="button" id="hud-reseed">reseed</button></div>';

    const stats = root.querySelector("#hud-stats");
    const thumbs = [];

    /**
     * Rasterizes one shape document into a thumbnail, replicating the arm about the
     * centre the way the atlas does, so the panel shows the crystal rather than a
     * drawing of it.
     * @param {HTMLCanvasElement} target the canvas to draw into
     * @param {string} svg the shape document
     * @returns {void}
     */
    function rasterize(target, svg) {
      const size = target.width;
      const ctx = target.getContext("2d");
      if (!ctx) {return;}
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, size, size);
      ctx.translate(size / 2, size / 2);
      const scale = (size - 4) / H.SHAPE_VIEW;
      ctx.scale(scale, scale);
      ctx.fillStyle = ctx.strokeStyle = "#cfe8ff";
      ctx.lineCap = "round";
      const parsed = H.shapePaths(svg);
      for (const deg of parsed.uses) {
        ctx.save();
        ctx.rotate(deg * Math.PI / 180);
        for (const part of parsed.paths) {
          const path = new Path2D(part.d);
          if (part.w > 0) {ctx.lineWidth = part.w; ctx.stroke(path);} else {ctx.fill(path);}
        }
        ctx.restore();
      }
    }

    /**
     * Redraws every thumbnail from the current seed, so the panel shows the crystals
     * this field is drawing rather than a generic sample of each habit.
     * @returns {void}
     */
    function drawThumbs() {
      for (const thumb of thumbs) {
        const spec = H.habitSpec(thumb.habit);
        // The seed the field built this habit's first crystal from, when the worker has
        // reported one; the panel's own draw otherwise.
        const seed = fieldSeeds.has(thumb.habit[0]) ? fieldSeeds.get(thumb.habit[0])
                                                    : prefs.seed + thumb.index * 104729;
        // A flat crystal is shown partway through its warp cycle, which is the pose a
        // flake spends most of its fall in; a wireframe solid has one pose.
        const svg = H.buildShape(H.prng(seed), H.SHAPE_VIEW, spec,
                                 H.FRAMES / 4, H.FRAMES);
        rasterize(thumb.canvas, svg);
      }
    }

    /**
     * The seed the field built each habit's first crystal from, as the worker reports it.
     *
     * A thumbnail painted from a seed of the panel's own is a different crystal from the
     * one the field is drawing under that name, and it stays that way through a reseed, a
     * variant switched on or off, and the rolling refresh that replaces crystals as flakes
     * recycle. Empty until the worker answers with its own, which is when the panel starts
     * showing the field rather than a plausible crystal of its own.
     */
    const fieldSeeds = new Map();

    /**
     * Takes the field's current per-habit seeds and repaints the tiles when any of them
     * moved.
     *
     * @param {Array<Array>} slots [habit name, slot seed] pairs, as the worker sends them
     * @returns {void}
     */
    function applySlots(slots) {
      if (!Array.isArray(slots)) {return;}
      let changed = false;
      for (const [name, seed] of slots) {
        if (fieldSeeds.get(name) === seed) {continue;}
        fieldSeeds.set(name, seed);
        changed = true;
      }
      if (changed) {drawThumbs();}
    }

    /**
     * How far the panel's own colour cycle has travelled, in degrees.
     *
     * The worker cycles the field at {@link H.CYCLE_DEGREES_PER_SEC}; the thumbnails are
     * still images the panel paints, so they cycle here at the same rate, driven off the
     * same clock, so a swatch shows the colour its crystal is being drawn in.
     */
    let cycleHue = 0;

    /**
     * Tints the thumbnails the way the field is tinted, so the panel's swatches show the
     * colours being drawn rather than the theme default. Each one carries the same share
     * of the spread its slot does, which is what makes the variation setting visible at
     * all: at 0% every swatch is the theme colour, and widening it fans the row across
     * the wheel the way the field does.
     * @returns {void}
     */
    function tintThumbs() {
      const spread = prefs.variation / 100;
      for (const thumb of thumbs) {
        const degrees = prefs.hue + cycleHue + thumb.spread * spread * H.HUE_SPREAD_MAX;
        thumb.canvas.style.filter = degrees ? "hue-rotate(" + degrees.toFixed(0) + "deg)" : "none";
      }
    }

    let cycleTimer = 0;
    let cycleAt = 0;

    /**
     * Starts or stops the thumbnails' cycle animation.
     *
     * Stopped while the panel is closed or the cycle is off: a hidden panel that keeps
     * repainting its swatches is work nobody is looking at.
     *
     * @returns {void}
     */
    function cycleLoop() {
      const want = prefs.cycle && prefs.open;
      if (want && !cycleTimer) {
        cycleAt = Date.now();
        // Frames, not a timer: the field cycles on the display's own beat and a swatch
        // that updates four times a second beside it visibly stutters.
        const step = () => {
          if (!prefs.cycle || !prefs.open) {cycleTimer = 0; return;}
          const now = Date.now();
          cycleHue = (cycleHue + (now - cycleAt) * H.CYCLE_DEGREES_PER_SEC / 1000) % 360;
          cycleAt = now;
          tintThumbs();
          cycleTimer = self.requestAnimationFrame(step);
        };
        cycleTimer = self.requestAnimationFrame(step);
      } else if (!want && cycleTimer) {
        self.cancelAnimationFrame(cycleTimer);
        cycleTimer = 0;
      }
      if (!prefs.cycle) {cycleHue = 0;}
    }

    const vars = root.querySelector("#hud-vars");
    H.HABITS.forEach((habit, index) => {
      const name = habit[0];
      // The tile is the control: the crystal is what the reader is deciding about, so the
      // decision is made by pressing it rather than by pressing a box beside it.
      const cell = document.createElement("button");
      cell.type = "button";
      cell.className = "hud-var";
      cell.classList.toggle("on", on(name));
      cell.setAttribute("aria-pressed", String(on(name)));
      cell.addEventListener("click", () => {
        prefs.enabled[name] = !on(name);
        cell.classList.toggle("on", on(name));
        cell.setAttribute("aria-pressed", String(on(name)));
        save();
        send({type: "habits", enabled: prefs.enabled});
      });
      const canvas = document.createElement("canvas");
      const ratio = Math.min(2, self.devicePixelRatio || 1);
      canvas.width = THUMB * ratio;
      canvas.height = THUMB * ratio;
      const label = document.createElement("span");
      label.className = "hud-name";
      label.textContent = name;
      cell.appendChild(canvas);
      cell.appendChild(label);
      vars.appendChild(cell);
      thumbs.push({
        habit,
        canvas,
        cell,
        index,
        // Spread across the row from one end of the range to the other, so the panel
        // shows the whole spread the field draws from rather than a random sample of it.
        spread: H.HABITS.length > 1 ? (index / (H.HABITS.length - 1)) * 2 - 1 : 0
      });
    });
    tintThumbs();

    /**
     * The panel's sliders: what each one sets, how its value is shown, and what the
     * worker is told when it moves.
     *
     * One table rather than eight near-identical handler blocks, because the reset button
     * and the panel's own start-up both have to know every setting there is, and a list
     * that has to be kept in step with itself in three places is how a setting ends up
     * reset by two of them and missed by the third.
     */
    const SLIDERS = [
      {id: "hud-count", key: "count", text: v => String(v),
       send: v => ({type: "quality", count: v})},
      {id: "hud-refresh", key: "refresh", text: v => v + "%",
       send: v => ({type: "refresh", chance: v / 100})},
      {id: "hud-hue", key: "hue", tint: true, text: v => v + "\u00b0",
       send: v => ({type: "hue", degrees: v}),
       // The reader moved it, so the page no longer decides the hue, and a box still
       // ticked whose effect the slider just overrode would be a lie. The value they
       // moved it to is their own, so it is also what switching the page's colour off
       // afterwards hands back.
       extra: () => {
         prefs.hueManual = prefs.hue;
         if (!prefs.context) {return;}
         prefs.context = false;
         context.checked = false;
       }},
      {id: "hud-variation", key: "variation", tint: true, text: v => v + "%",
       send: v => ({type: "variation", percent: v})},
      {id: "hud-gravity", key: "gravity", text: v => v + "%",
       send: v => ({type: "motion", gravity: v / 100})},
      {id: "hud-gravity-var", key: "gravityVar", text: v => v + "%",
       send: v => ({type: "motion", gravityVar: v / 100})},
      {id: "hud-spin", key: "spin", text: v => v + "%",
       send: v => ({type: "motion", spin: v / 100})},
      {id: "hud-spin-var", key: "spinVar", text: v => v + "%",
       send: v => ({type: "motion", spinVar: v / 100})},
      {id: "hud-size", key: "size", text: v => v + "%",
       send: v => ({type: "size", scale: v / 100})},
      {id: "hud-size-var", key: "sizeVar", text: v => v + "%",
       send: v => ({type: "size", variation: v / 100})}
    ];

    for (const row of SLIDERS) {
      const control = root.querySelector("#" + row.id);
      control.value = prefs[row.key];
      paint(control);
      root.querySelector("#" + row.id + "-v").textContent = row.text(prefs[row.key]);
      control.addEventListener("input", () => {
        prefs[row.key] = Number(control.value);
        paint(control);
        root.querySelector("#" + row.id + "-v").textContent = row.text(prefs[row.key]);
        if (row.tint) {tintThumbs();}
        if (row.extra) {row.extra();}
        save();
        send(row.send(prefs[row.key]));
      });
    }

    /** The flakes slider, needed by the arrow-key shortcut below. */
    const count = root.querySelector("#hud-count");

    const pause = root.querySelector("#hud-pause");

    /**
     * Puts the pause button's state on its own label.
     *
     * The word changes with the state instead of the button keeping one name: a field that
     * has already stopped is not offering to stop again, it is offering snow. aria-pressed
     * carries the same fact for a reader who hears the control rather than reads it.
     *
     * @returns {void}
     */
    function paintPause() {
      pause.textContent = prefs.paused ? "snow" : "pause";
      pause.setAttribute("aria-pressed", String(prefs.paused));
    }

    /**
     * Stops the field or starts it again, and tells the button and the worker.
     *
     * @param {boolean} paused true to take the field off the screen
     * @returns {void}
     */
    function setPaused(paused) {
      prefs.paused = paused;
      paintPause();
      save();
      send({type: "visible", visible: !prefs.paused});
    }

    paintPause();
    pause.addEventListener("click", () => {setPaused(!prefs.paused);});

    const cycle = root.querySelector("#hud-cycle");
    cycle.checked = prefs.cycle;
    cycle.addEventListener("change", () => {
      prefs.cycle = cycle.checked;
      save();
      cycleLoop();
      tintThumbs();
      send({type: "cycle", on: prefs.cycle});
    });
    cycleLoop();

    /**
     * The hue the reader had set before the page's own colour took it over, or null when
     * the page is not in charge.
     *
     * The hue slider is the one number the context setting writes, so it is the one thing
     * that has to be handed back when the setting comes off. It lives in prefs rather
     * than in a local because a page left with context colours on reloads with the page's
     * hue already in the hue setting, and the reader's own value would be gone.
     */
    const context = root.querySelector("#hud-context");
    context.checked = prefs.context;
    context.addEventListener("change", () => {
      prefs.context = context.checked;
      save();
      if (prefs.context) {
        applyContext();
        return;
      }
      // Off means the field goes back to the colour it had before the page took over —
      // the reader's own hue, or the theme's colour untouched when they never set one —
      // not to whatever point of the wheel the page happened to leave it at.
      if (prefs.hueManual !== null || prefs.hue !== 0) {
        prefs.hue = prefs.hueManual === null ? 0 : prefs.hueManual;
        prefs.hueManual = null;
        syncControls();
        tintThumbs();
        send({type: "hue", degrees: prefs.hue});
      }
    });

    /**
     * Reads an rgb() or rgba() colour out of a computed style.
     *
     * @param {string} text the colour as the browser reports it
     * @returns {number[]|null} the colour as [r, g, b], or null when it is not opaque
     */
    function parseColor(text) {
      const m = /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?/.exec(String(text));
      if (!m) {return null;}
      const alpha = m[4] === undefined ? 1 : Number(m[4]);
      if (alpha < 0.05) {return null;}
      return [Number(m[1]), Number(m[2]), Number(m[3])];
    }

    /**
     * How much colour a colour carries, 0 for a grey and 1 for a full one.
     *
     * @param {number[]} rgb the colour as [r, g, b]
     * @returns {number} the saturation in [0, 1]
     */
    function saturation(rgb) {
      const max = Math.max(rgb[0], rgb[1], rgb[2]);
      const min = Math.min(rgb[0], rgb[1], rgb[2]);
      return max <= 0 ? 0 : (max - min) / max;
    }

    /**
     * Adds one colour to the page tally.
     *
     * The buckets are coarse on purpose: a gradient, the panel behind it and the text on
     * top of it are one colour to a reader, and a tally that kept them apart would find
     * a hundred shades of the same blue and no answer.
     *
     * @param {Map<number,Object>} buckets the tally, keyed by the quantised colour
     * @param {number[]|null} rgb the colour to add
     * @param {number} weight the area it covers, in square pixels
     * @returns {void}
     */
    function addColor(buckets, rgb, weight) {
      if (!rgb || weight <= 0) {return;}
      const key = ((rgb[0] >> 5) << 10) | ((rgb[1] >> 5) << 5) | (rgb[2] >> 5);
      const bucket = buckets.get(key) || {weight: 0, r: 0, g: 0, b: 0, n: 0};
      bucket.weight += weight;
      bucket.r += rgb[0];
      bucket.g += rgb[1];
      bucket.b += rgb[2];
      bucket.n++;
      buckets.set(key, bucket);
    }

    /**
     * The colour the page is made of, or null when the page has no colour to answer with.
     *
     * A page cannot photograph itself, so this reads the computed colours of the elements
     * the snow falls over and weights each by the area it covers: the answer is what the
     * viewport is mostly made of, not the brightest control on it. Text counts at a
     * quarter of its box, because a page of coloured text on a dark ground is a page of
     * that colour. Grey is skipped — a dark theme's biggest colour is its background, and
     * a grey background has no side of the wheel to be opposite.
     *
     * @returns {number[]|null} the predominant colour as [r, g, b], or null
     */
    function pageColor() {
      const buckets = new Map();
      const nodes = document.querySelectorAll("body *");
      const limit = Math.min(nodes.length, SAMPLE_NODES);
      for (let i = 0; i < limit; i++) {
        const node = nodes[i];
        // The snow canvas is the thing being coloured, and the panel is the reader's own
        // controls; neither belongs in the answer.
        if (node.tagName === "CANVAS" || root.contains(node)) {continue;}
        const box = node.getBoundingClientRect();
        const area = box.width * box.height;
        if (area < 64) {continue;}
        const style = self.getComputedStyle(node);
        addColor(buckets, parseColor(style.backgroundColor), area);
        addColor(buckets, parseColor(style.color), area / 4);
      }
      let best = null;
      let bestScore = 0;
      for (const bucket of buckets.values()) {
        const rgb = [bucket.r / bucket.n, bucket.g / bucket.n, bucket.b / bucket.n];
        const chroma = saturation(rgb);
        if (chroma < 0.08) {continue;}
        const score = bucket.weight * (0.3 + chroma);
        if (score > bestScore) {bestScore = score; best = rgb;}
      }
      return best;
    }

    /**
     * Points the flakes at the far side of the wheel from the page they fall over.
     *
     * Snow that matches the page disappears into it; snow on the opposite side reads as
     * snow. The hue slider turns the colour the page paints snow in, so the turn to ask
     * for is the distance from that colour to the opposite of the page's own — and the
     * slider is then moved to show where the page put it, rather than hiding a second
     * hue the reader cannot see or correct.
     *
     * @returns {void}
     */
    function applyContext() {
      const page = pageColor();
      if (!page) {return;}
      if (prefs.hueManual === null) {prefs.hueManual = prefs.hue;}
      const base = H.parseHex(opts.baseColor) || [135, 206, 235];
      const turn = ((H.hueOf(page) + 180 - H.hueOf(base)) % 360 + 360) % 360;
      prefs.hue = (Math.round(turn / 5) * 5) % 360;
      syncControls();
      tintThumbs();
      save();
      send({type: "hue", degrees: prefs.hue});
    }

    /**
     * Writes the panel's settings into its controls.
     *
     * The randomize button changes settings it did not come from a control, so the
     * sliders, boxes and swatches have to be told what they now say.
     *
     * @returns {void}
     */
    function syncControls() {
      for (const row of SLIDERS) {
        const control = root.querySelector("#" + row.id);
        control.value = prefs[row.key];
        paint(control);
        root.querySelector("#" + row.id + "-v").textContent = row.text(prefs[row.key]);
      }
      paintPause();
      cycle.checked = prefs.cycle;
      context.checked = prefs.context;
      for (const thumb of thumbs) {
        thumb.cell.classList.toggle("on", on(thumb.habit[0]));
        thumb.cell.setAttribute("aria-pressed", String(on(thumb.habit[0])));
      }
    }

    /**
     * Pushes every setting the panel owns at the worker.
     *
     * @returns {void}
     */
    function sendAll() {
      send({type: "habits", enabled: prefs.enabled});
      for (const row of SLIDERS) {send(row.send(prefs[row.key]));}
      send({type: "cycle", on: prefs.cycle});
      send({type: "visible", visible: !prefs.paused});
    }

    /**
     * Draws a fresh set of crystals and replaces the ones on screen with them.
     *
     * @returns {void}
     */
    function reseed() {
      prefs.seed = Date.now() & 0x7fffffff;
      save();
      // The worker re-deals its own slots and replaces every flake on the field, so the
      // viewport changes now; a page restart would also rebuild the canvas and the
      // handshake for no reason the reader can see.
      send({type: "reseed", seed: prefs.seed});
      drawThumbs();
      tintThumbs();
    }

    root.querySelector("#hud-reseed").addEventListener("click", reseed);

    /**
     * Puts every setting back to what a field starts with.
     *
     * The seed is left alone: reset answers "give me the defaults", not "give me a
     * different field", and a reader who wanted a new field presses reseed beside it.
     *
     * @returns {void}
     */
    function resetPrefs() {
      prefs.count = H.MAX_FLAKES;
      prefs.refresh = H.CRYSTAL_REFRESH_CHANCE * 100;
      prefs.hue = 0;
      prefs.variation = H.DEFAULT_VARIATION * 100;
      prefs.gravity = 100;
      prefs.gravityVar = H.DEFAULT_GRAVITY_VAR * 100;
      prefs.spin = 100;
      prefs.spinVar = H.DEFAULT_SPIN_VAR * 100;
      prefs.size = H.DEFAULT_SIZE * 100;
      prefs.sizeVar = H.DEFAULT_SIZE_VAR * 100;
      prefs.cycle = false;
      prefs.context = false;
      // Back to the theme's own colour: the hue the reader had set for themselves goes
      // with the hue, or a later context-colour page would hand this value back.
      prefs.hueManual = null;
      prefs.paused = false;
      // Back to the field the card starts with, snowman included in what is switched off:
      // an empty map would mean every habit is in play, not the defaults.
      prefs.enabled = defaultEnabled();
      save();
      syncControls();
      cycleLoop();
      tintThumbs();
      sendAll();
    }

    root.querySelector("#hud-reset").addEventListener("click", resetPrefs);

    root.querySelector("#hud-random").addEventListener("click", () => {
      const pick = (min, max, step) => min + Math.floor(Math.random() * ((max - min) / step + 1)) * step;
      prefs.seed = Date.now() & 0x7fffffff;
      prefs.count = pick(10, H.TUNING_MAX_FLAKES, 1);
      prefs.refresh = pick(0, 100, 5);
      prefs.hue = pick(0, 355, 5);
      // The hue is drawn from the wheel here, so it is the reader's own and not the
      // page's; context colours come off below.
      prefs.hueManual = prefs.hue;
      prefs.variation = pick(0, 100, 5);
      prefs.gravity = pick(40, 220, 5);
      prefs.gravityVar = pick(0, 100, 5);
      prefs.spin = pick(0, 240, 5);
      prefs.spinVar = pick(0, H.SPIN_VAR_MAX * 100, 5);
      // Sizes stay inside the middle of the range: a field of very large or very small
      // flakes is a randomization that stops looking like snow at all.
      prefs.size = pick(70, 160, 5);
      prefs.sizeVar = pick(0, 100, 5);
      prefs.cycle = Math.random() < 0.5;
      // The hue is drawn from the wheel here, so the page's own colour is not in charge.
      prefs.context = false;
      // Two thirds of the variants stay in play: a field with one habit left is a
      // randomization too flat to read as one.
      prefs.enabled = {};
      for (const habit of H.HABITS) {prefs.enabled[habit[0]] = Math.random() < 0.67;}
      save();
      syncControls();
      sendAll();
      reseed();
      cycleLoop();
    });
    // The close button is not a hide, it is an end: the loader stores the choice in the card's
    // own key and takes the card's module and stylesheet back off the page, which leaves the
    // field exactly as it was — running, or not running. Without a loader to unload it, as in
    // a page that pulled this module in by hand, the card simply closes.
    root.querySelector(".hud-x").addEventListener("click", () => {
      if (self.snowBoot && typeof self.snowBoot.closeHud === "function") {self.snowBoot.closeHud();}
      else {setOpen(false);}
    });

    /**
     * Shows or hides the panel, and remembers which.
     * @param {boolean} isOpen whether the panel is visible
     * @returns {void}
     */
    function setOpen(isOpen) {
      prefs.open = isOpen;
      root.classList.toggle("open", isOpen);
      save();
      cycleLoop();
    }

    // Keyboard shortcuts, once the panel is on screen. The gesture that opens it is the
    // page's to catch, not this module's: the panel only exists while the field runs, and
    // typing the gesture on a page whose field is off has to start the field.
    self.addEventListener("keydown", event => {
      if (event.ctrlKey || event.metaKey || event.altKey) {return;}
      // A console page is full of text fields; typing in one of them is not a shortcut
      // and r is not a reseed.
      const field = event.target;
      if (field && (field.tagName === "INPUT" || field.tagName === "TEXTAREA" ||
                    field.tagName === "SELECT" || field.isContentEditable)) {return;}
      if (event.key === "r") {
        reseed();
      } else if (event.key === " ") {
        setPaused(!prefs.paused);
      } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        const step = event.key === "ArrowUp" ? 2 : -2;
        prefs.count = Math.max(0, Math.min(H.TUNING_MAX_FLAKES, prefs.count + step));
        count.value = prefs.count;
        paint(count);
        root.querySelector("#hud-count-v").textContent = String(prefs.count);
        save();
        send({type: "quality", count: prefs.count});
      }
    });

    root.classList.toggle("open", prefs.open);

    // A field left with context colours on re-reads the page it is now on: the page that
    // saved the setting and the page being loaded need not be the same colour. Deferred a
    // beat so a stylesheet or an image that arrives late is in the sample.
    if (prefs.context) {self.setTimeout(applyContext, 400);}
    self.addEventListener("resize", () => {
      if (prefs.context) {applyContext();}
    });

    return {
      prefs,
      /**
       * Pushes the panel's settings at a field the page has just started.
       *
       * The page sends the worker's init message — it owns the viewport, the colour and
       * the canvas — and the panel follows it with the settings that are its own, so a
       * restart cannot come back with the variants switched on that were switched off.
       *
       * @param {Object} [bounds] unused by the panel, kept so the page can pass what it
       *   passed to init
       * @param {function(number): void} init starts the field at the given seed
       * @returns {void}
       */
      applyTo: (bounds, init) => {
        init(prefs.seed);
        drawThumbs();
        sendAll();
        tintThumbs();
      },
      /**
       * Puts the panel on the page.
       *
       * The page owns the typed gesture that opens the panel, because the panel only
       * exists while the field runs and the gesture has to work on a page whose field is
       * off. It calls this once the field — and so the panel — is there.
       *
       * @returns {void}
       */
      open: () => setOpen(true),
      /**
       * Takes the panel off the page.
       * @returns {void}
       */
      close: () => setOpen(false),
      /**
       * Feeds one stats message from the worker into the panel's header.
       *
       * The stats message carries the seeds the field's crystals were built from, which is
       * how the thumbnails keep up with a rolling refresh: that replaces crystals as flakes
       * recycle, with no message of its own.
       * @param {Object} msg the worker's stats message
       * @returns {void}
       */
      stats: msg => {
        if (!stats) {return;}
        stats.textContent = "seed " + prefs.seed + "  " + Math.round(msg.fps || 0) + " fps";
        applySlots(msg.slots);
      },
      /**
       * Tells the panel which seeds the field's crystals were built from.
       *
       * Sent by the page from the worker's ready and slots messages, so the card's tiles
       * show the crystals the field is drawing rather than the panel's own draw of the
       * same habits.
       *
       * @param {Array<Array>} slots [habit name, slot seed] pairs
       * @returns {void}
       */
      field: slots => applySlots(slots)
    };
  }

  self.SNOWHUD = Object.freeze({attach, GESTURE, STORE});
}());
