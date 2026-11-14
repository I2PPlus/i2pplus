/*
    Physics and rendering worker for the I2P+ console's festive snow.

    The drift, deterioration and death-spin constants are inherited from
    Snowflakes © 2024 Denis Seleznev (MIT, https://github.com/hcodes/snowflakes/).
    Worker transport, sprite atlas and offscreen rendering for I2P+ by dr|z3d,
    AGPLv3 or later.
*/

/**
 * @module snowman
 * @description Dedicated worker that drives the router console's snow. It owns
 * every flake's state, integrates the drift on a fixed timestep, and paints the
 * scene itself when the page hands it an OffscreenCanvas; where the browser will
 * not, it posts one packed Float32Array of draw transforms per frame and lets the
 * page paint them. Either way the page runs no animation math.
 *
 * The physics constants are written per 60Hz frame, exactly as the original DOM
 * animation had them, and applied by a fixed 16.667ms step. A 30Hz or 144Hz
 * display therefore gets the same fall speed and the same sparkle noise as a 60Hz
 * one, which the per-frame version could not promise.
 *
 * It also draws the flakes. Each one is a generated SVG crystal: a single arm of
 * filled, tapered spikes, replicated six times about the centre, so the six-fold
 * symmetry is a property of the construction rather than of the numbers chosen.
 * One shape per flake slot, about 1.7 KB of markup and 0.05 ms each, sent to the
 * page with the ready message for it to rasterize into the sprite atlas. That
 * replaces fetching the six hand-drawn files, which are kept only as a fallback.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

(() => {
  'use strict';

  /** One 60Hz frame in milliseconds; the fixed simulation step. */
  const FRAME_MS = 1000 / 60;
  /**
   * Steps run per frame at most. A tab that was throttled or a machine that fell
   * behind gets its backlog dropped rather than a burst of catch-up steps.
   */
  const MAX_STEPS = 4;
  /** Floats per flake in the frame buffer. */
  const STRIDE = 8;
  /** Edge length in pixels of one sprite-atlas cell. */
  const CELL = 64;
  /**
   * Flake count bounds, and the viewport area in CSS pixels one flake is worth.
   * Chosen to land in the same 12–20 range the original drew at random, but
   * deterministically and scaled to the window instead of to a dice roll.
   */
  const MIN_FLAKES = 10;
  const MAX_FLAKES = 20;
  const AREA_PER_FLAKE = 80000;
  /** Steps a dying flake spins before it starts to fall out; 200ms at 60Hz. */
  const DEATH_SPIN_STEPS = Math.round(200 / FRAME_MS);
  /**
   * The size, in CSS pixels, below which a flake is spent and starts its fall out.
   * The travel envelope is written to reach this at the end of a flake's travel.
   */
  const REMOVAL_SIZE = 9;
  /**
   * How fast a dying flake's spin ramps up, as a multiplier per step.
   *
   * Multiplicative rather than a fixed increment, because the spin is seeded from
   * the rate the flake was already turning at: a fixed increment would be lost on a
   * flake that was already turning fast, and would take a slow one most of the
   * spiral to reach anything that reads as a spin at all.
   */
  const DEATH_SPIN_GROWTH = 1.33;
  /**
   * The rate a dying flake's spin ramps toward, in radians per step.
   *
   * This is a ceiling, not a target reached and left: the old model multiplied the
   * spin every step of the fall-out with no bound, and measured over four seeds it
   * arrived at 0.3-3.4 radians a step by the end of the spin delay and at up to
   * 73,000 by the end of the fall — 39 of 47 steps past the point where the motion
   * stops being resolvable. A six-fold symmetric flake looks the same every
   * 2π/6 radians, so sampling it once a step can only follow it up to half that,
   * 0.524 radians a step; past that the sprite aliases and reads as a shimmer
   * rather than as a turn.
   *
   * The ceiling is on the envelope, not on the instantaneous spin, so it is chosen
   * from the wobble: {@link DEATH_SPIN_BIAS} plus {@link DEATH_WOBBLE_MAX} is 1.95,
   * and 0.27 times that is 0.53, which sits right at the sampling limit. A reversal
   * is then seen happening rather than inferred from a flicker.
   */
  const DEATH_SPIN_MAX = 0.27;
  /**
   * The steady part of a dying flake's spin, as a fraction of the envelope.
   *
   * The spin is the sum of this steady part and a wobble of {@link DEATH_WOBBLE_MAX}
   * or more, so the total passes through zero twice a period: the flake visibly
   * slows, stalls, and comes back the other way, the way a leaf does. A smaller
   * bias keeps spinning one way; a larger one never quite stalls.
   */
  const DEATH_SPIN_BIAS = 0.45;
  /** Chance a dying flake's steady part is against the direction it was turning in. */
  const DEATH_SPIN_REVERSE = 0.25;
  /** Wobble rate bounds in radians per step; the period is 2π over these. */
  const DEATH_WOBBLE_RATE_MIN = 0.3;
  const DEATH_WOBBLE_RATE_MAX = 0.52;
  /**
   * Wobble amplitude bounds, as a fraction of the envelope. The lower bound is
   * what makes a reversal possible at all: the wobble has to be able to cancel the
   * steady part, so it cannot be smaller than 1 - {@link DEATH_SPIN_BIAS}.
   */
  const DEATH_WOBBLE_MIN = 1 - DEATH_SPIN_BIAS;
  const DEATH_WOBBLE_MAX = 1.5;
  /**
   * How much of its sideways velocity a dying flake keeps, per step.
   *
   * A living flake's sideways motion is a sway — a position read off a phase, with
   * no momentum behind it — so the old death spiral simply stopped applying it and
   * the flake dropped straight down from wherever the sway had left it. Measured,
   * that trace is 2-18% as wide as it is long: a dead drop. Snapshotting the sway's
   * rate at the moment the flake lets go and decaying it gives the fall-out an arc
   * that starts on the heading the flake was already on.
   */
  const DEATH_DRIFT_DECAY = 0.985;
  /**
   * Sideways travel a spinning flake picks up per step, per radian of its spin.
   *
   * A flake tumbling fast does not fall along a straight line, and coupling a
   * little of the spin into the drift is what makes the fall-out read as a spiral
   * rather than as a spin in place.
   */
  const SPIN_LATERAL = 0.3;
  /** Downward acceleration once the spin delay is over, pixels per step per step. */
  const DEATH_FALL_ACCEL = 0.3;
  /** Wind sway phase advance per step, in radians. */
  const WIND_STEP = 0.02;
  /**
   * How far apart two flakes get before they stop noticing each other, as a
   * multiple of the sum of their sizes. Comfortably more than touching, so they
   * part before they overlap, and not so much that the whole field is one crowd.
   */
  const SOFT_REACH = 1.45;
  /** Acceleration a near-miss applies, in pixels per step per step at full overlap. */
  const SEPARATION = 0.055;
  /** How much of a gust survives a step. The push is spread over many steps rather
   *  than applied at once, which is what keeps a near-miss from reading as a jump. */
  const GUST_DECAY = 0.93;
  /** Ceiling on a gust's sideways rate, so a crowd cannot throw a flake. */
  const GUST_MAX = 0.7;
  /** How much of an off-centre near-miss goes into spin rather than into drift. */
  const GRAZE_SPIN = 0.05;
  /** How often the worker reports its measured frame rate back to the page. */
  const STATS_MS = 2000;
  /**
   * Largest paint divisor accepted here, a clamp on what the page can ask for.
   *
   * Physics is untouched by the divisor: the fixed timestep keeps the field falling
   * at the same speed whatever it does, so a divisor of 2 halves the draw cost and
   * leaves the motion identical. The page owns the real bound, because only it
   * knows the panel rate the divisor is relative to — two beats is 30 frames a
   * second on a 60Hz display and 72 on a 144Hz one — and it works that out so the
   * reader never sees fewer than 30. This number only stops a bad measurement
   * asking for something absurd.
   */
  const RATE_MAX = 4;

  /** Frame-buffer slot holding the x translation, in device pixels. */
  const F_X = 0;
  /** Frame-buffer slot holding the y translation, in device pixels. */
  const F_Y = 1;
  /** Frame-buffer slots holding the 2x3 draw transform, scale and rotation baked in. */
  const F_A = 2;
  const F_B = 3;
  const F_C = 4;
  const F_D = 5;
  /** Frame-buffer slot holding the flake's opacity. */
  const F_ALPHA = 6;
  /** Frame-buffer slot holding the flake's index into the sprite atlas. */
  const F_CELL = 7;

  /**
   * Festive tints mixed into the theme colour, inherited from the original.
   * Kept as a table so the mix stays a table lookup rather than per-flake branches.
   */
  const FESTIVE_TINTS = [
    [255, 69, 0],
    [255, 140, 0],
    [255, 215, 0],
    [255, 192, 203],
    [186, 85, 211],
    [220, 20, 60]
  ];

  /** Wind strengths, weakest to strongest; a flake drifts up this scale as it ages. */
  const WIND_SPEEDS = [0.15, 0.25, 0.35, 0.5, 0.8];

  /**
   * Edge length, in the generator's own units, of the square a generated flake
   * fills. The atlas cell is {@link CELL} pixels and the shape is drawn to fill
   * it, so this only fixes the generator's coordinate scale.
   */
  const SHAPE_VIEW = 100;

  /**
   * Chance a flake's arms are drawn with a spiral warp rather than straight out.
   *
   * A warped flake is a different crystal from a straight one at the same size, so
   * this multiplies the variety the atlas holds without adding a habit: the warp
   * applies to whichever arm builder was picked.
   *
   * It is also what animates a flat crystal — a slot's frames are the same arm at
   * successive points of the warp cycle — so an unwrapped flake has a frame strip of
   * eight identical cells and simply does not move. Below about half the field
   * warped, the animation stops reading as something the field does.
   */
  const WARP_CHANCE = 0.6;
  /**
   * Largest twist a warped flake gets, in radians, measured at the centre and
   * falling to nothing at the edge of the drawing.
   *
   * Strongest at the middle is the direction that reads: a flake twisted hardest at
   * its tips and left straight through the middle looks bent rather than swirled.
   * Past about 0.5 radians the six arms start to overlap and the crystal stops
   * reading as six-fold.
   */
  const WARP_MAX = 0.45;
  /**
   * Frames held per flake slot.
   *
   * For a flat crystal the frames are the same arm drawn at successive points of a
   * slow warp cycle, so the flake appears to untwist and re-twist; for the wireframe
   * habit they are successive orientations of the same solid. Either way the draw
   * cost is unchanged — a frame is a different source rectangle in the same atlas,
   * not a re-render — and the worker rasterizes all of them itself, measured at
   * 1.3 ms for 120 cells and about 3.5 ms for this many.
   *
   * Sixteen rather than eight because the wireframe's tumble is the binding
   * constraint: at eight frames the solid jumps 45 degrees at a time and reads as a
   * flip book.
   */
  const FRAMES = 16;
  /** Simulation steps a flat flake's frame is held for; the reforming cycle's pace. */
  const FRAME_BEATS = 12;
  /** Simulation steps a wireframe's orientation is held for; its tumble pace. */
  const TUMBLE_BEATS = 5;
  /**
   * Stroke width of a wireframe edge nearer the viewer, in shape units.
   *
   * Kept low deliberately. A wireframe covers far more of the atlas cell than a
   * crystal of filled spikes does — measured at 3.5 times the alpha coverage at
   * these widths — and a flake shown at 11-16 pixels with that much ink in it reads
   * as a fuzzy ball rather than as an open framework.
   */
  const WIRE_STROKE_NEAR = 4;
  /** Stroke width of one farther away, in shape units. */
  const WIRE_STROKE_FAR = 2.5;
  /**
   * The twist in force while an arm is being built, or null for straight arms.
   *
   * Module state rather than a parameter on every primitive because the twist is a
   * property of the flake being drawn, not of each element: the arm builders stay
   * unchanged and every one of them gets the warp for free. Nothing else calls the
   * primitives while this is set, and buildShape clears it as soon as the arm is
   * built, so a direct spike() call from a test sees no warp.
   */
  let warp = null;

  /**
   * Twist angle, in radians, a point is turned through for being that far from the
   * flake's centre.
   *
   * @param {number} x point x in shape units
   * @param {number} y point y in shape units
   * @returns {number} the twist, 0 for a straight flake or a centred element
   */
  function warpAt(x, y) {
    if (!warp) {return 0;}
    const r = Math.sqrt(x * x + y * y);
    if (r < 1) {return 0;}
    return warp.k * (1 - Math.min(1, r / warp.radius));
  }

  /**
   * Rotates a point about the flake's centre.
   *
   * The same matrix turns a direction through the same angle by adding it to the
   * angle argument, which is why a spike can warp its base and its heading with one
   * number: (sin a, -cos a) rotated by t is (sin(a+t), -cos(a+t)).
   *
   * @param {number} x point x in shape units
   * @param {number} y point y in shape units
   * @param {number} twist rotation in radians
   * @returns {number[]} the rotated [x, y]
   */
  function warpPoint(x, y, twist) {
    const c = Math.cos(twist);
    const s = Math.sin(twist);
    return [x * c - y * s, x * s + y * c];
  }

  /**
   * Picks a flake count from the viewport area, bounded so a huge monitor does not
   * buy a huge cost and a small window still reads as snow.
   *
   * @param {number} width viewport width in CSS pixels
   * @param {number} height viewport height in CSS pixels
   * @returns {number} flake count in [{@link MIN_FLAKES}, {@link MAX_FLAKES}]
   */
  function flakeCountFor(width, height) {
    const area = Math.max(0, width) * Math.max(0, height);
    const scaled = Math.round(area / AREA_PER_FLAKE);
    return Math.min(MAX_FLAKES, Math.max(MIN_FLAKES, scaled));
  }

  /**
   * Lays out {@link count} atlas cells in a near-square grid.
   *
   * @param {number} count number of cells to hold
   * @returns {{cols: number, rows: number, width: number, height: number}} grid
   *   dimensions in cells and in pixels
   */
  function atlasLayout(count) {
    const cols = Math.max(1, Math.ceil(Math.sqrt(Math.max(1, count))));
    const rows = Math.max(1, Math.ceil(Math.max(1, count) / cols));
    return {cols, rows, width: cols * CELL, height: rows * CELL};
  }

  /**
   * Deterministic 32-bit PRNG (mulberry32). Seeded so a test run reproduces a
   * flake field exactly; the page seeds from the clock in normal use.
   *
   * @param {number} seed initial state
   * @returns {function(): number} generator yielding [0, 1)
   */
  function prng(seed) {
    let s = seed >>> 0;
    return function () {
      s = (s + 0x6D2B79F5) >>> 0;
      let t = s;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /**
   * Parses a #rrggbb theme colour.
   *
   * @param {string} hex colour in #rrggbb or rrggbb form
   * @returns {number[]|null} [r, g, b], or null when the text is not a hex colour
   */
  function parseHex(hex) {
    const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(String(hex));
    if (!m) {return null;}
    return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
  }

  /**
   * Jitters a theme colour into one flake's colour, half the time mixing in a
   * festive tint first. Returns an rgb() string because that is what the SVG
   * template's :color: placeholder is substituted with.
   *
   * @param {number[]} base [r, g, b] of the theme colour
   * @param {number} maxVar jitter amplitude in 1/255ths
   * @param {function(): number} rnd source of randomness
   * @returns {string} an rgb() colour string
   */
  function varyColor(base, maxVar, rnd) {
    const clamp = v => Math.max(0, Math.min(255, v | 0));
    if (rnd() < 0.5) {
      const tint = FESTIVE_TINTS[Math.floor(rnd() * FESTIVE_TINTS.length)];
      const mix = 0.075;
      return "rgb(" + clamp(base[0] * (1 - mix) + tint[0] * mix + rnd() * maxVar - maxVar / 2) + "," +
                      clamp(base[1] * (1 - mix) + tint[1] * mix + rnd() * maxVar - maxVar / 2) + "," +
                      clamp(base[2] * (1 - mix) + tint[2] * mix + rnd() * maxVar - maxVar / 2) + ")";
    }
    return "rgb(" + clamp(base[0] + rnd() * maxVar * 3 - maxVar) + "," +
                    clamp(base[1] + rnd() * maxVar * 3 - maxVar) + "," +
                    clamp(base[2] + rnd() * maxVar * 3 - maxVar) + ")";
  }

  /**
   * Builds the appearance table the page rasterizes into the sprite atlas: one
   * entry per flake slot, each naming which flake shape and which colour that
   * slot draws. Generated once, up front, so the atlas never has to be rebuilt
   * when the adaptive quality controller adds or removes flakes.
   *
   * @param {number} count number of slots
   * @param {string} baseColor theme colour as #rrggbb
   * @param {function(): number} rnd source of randomness
   * @param {number} shapeCount how many shapes the page has to draw from
   * @returns {Array<{type: number, color: string}>} one entry per slot, each
   *   naming a 1-based shape index and the colour that shape is painted
   */
  function buildPalette(count, baseColor, rnd, shapeCount) {
    const rgb = parseHex(baseColor) || [135, 206, 235];
    const shapes = Math.max(1, shapeCount | 0);
    const cells = [];
    for (let i = 0; i < count; i++) {
      // One shape per slot when the worker generated that many, so every flake in
      // the field is a different crystal. A shared pool — the hand-drawn files,
      // used when generation is unavailable — is instead picked at random, which
      // spreads the six shapes across the field rather than cycling them.
      const type = shapes >= count ? i + 1 : Math.floor(rnd() * shapes) + 1;
      cells.push({type, color: varyColor(rgb, 10, rnd)});
    }
    return cells;
  }

  /**
   * Writes one coordinate into shape markup.
   *
   * One decimal place is far below the sub-pixel step at the {@link CELL} pixel
   * atlas cell, and it is what keeps a generated flake smaller than the
   * hand-drawn files it replaces.
   *
   * @param {number} v the coordinate
   * @returns {string} v rounded to a tenth
   */
  function coord(v) {
    return String(Math.round(v * 10) / 10);
  }

  /**
   * Builds one filled, tapered spike: a quad from a base half-width down to a
   * point.
   *
   * Every part of a generated flake is one of these, so the crystal is a filled
   * silhouette like the hand-drawn files. A stroked line of the same shape reads
   * as a hairline once the {@link CELL} pixel atlas cell is drawn at the 11-16
   * pixels a flake is actually shown at, and effectively disappears.
   *
   * A spike placed away from the centre is turned by {@link warpAt} while a warped
   * flake is being built, which is what bends its arms into a pinwheel.
   *
   * @param {number} x base x in shape units
   * @param {number} y base y in shape units
   * @param {number} angle direction in radians, 0 pointing up
   * @param {number} len length along that direction
   * @param {number} baseHalf half width at the base
   * @param {number} tipHalf half width at the tip, 0 for a point
   * @returns {string} one filled path element
   */
  function spike(x, y, angle, len, baseHalf, tipHalf) {
    const twist = warpAt(x, y);
    if (twist) {
      const at = warpPoint(x, y, twist);
      x = at[0];
      y = at[1];
      angle += twist;
    }
    const dx = Math.sin(angle);
    const dy = -Math.cos(angle);
    const px = Math.cos(angle);
    const py = Math.sin(angle);
    return '<path d="M' + coord(x + px * baseHalf) + ' ' + coord(y + py * baseHalf) +
           'L' + coord(x + dx * len + px * tipHalf) + ' ' + coord(y + dy * len + py * tipHalf) +
           'L' + coord(x + dx * len - px * tipHalf) + ' ' + coord(y + dy * len - py * tipHalf) +
           'L' + coord(x - px * baseHalf) + ' ' + coord(y - py * baseHalf) + 'Z"/>';
  }

  /**
   * Writes a regular polygon's outline as path data.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius distance from centre to vertex
   * @param {number} sides number of corners, at least 3
   * @param {number} rotation orientation in radians
   * @returns {string} the closed path data, with no element around it
   */
  function ngonPath(cx, cy, radius, sides, rotation) {
    const twist = warpAt(cx, cy);
    if (twist) {
      const at = warpPoint(cx, cy, twist);
      cx = at[0];
      cy = at[1];
      rotation += twist;
    }
    let d = "";
    for (let k = 0; k < sides; k++) {
      const a = rotation + k * 2 * Math.PI / sides;
      d += (k ? "L" : "M") + coord(cx + Math.sin(a) * radius) + " " + coord(cy - Math.cos(a) * radius);
    }
    return d + "Z";
  }

  /**
   * Writes a hexagon outline as path data.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius distance from centre to vertex
   * @param {number} rotation orientation in radians
   * @returns {string} the closed path data, with no element around it
   */
  function hexPath(cx, cy, radius, rotation) {
    return ngonPath(cx, cy, radius, 6, rotation);
  }

  /**
   * Builds one filled hexagon. Real crystals grow flat faces, and a hexagon is
   * the only element here that is already six-fold symmetric by itself.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius distance from centre to vertex
   * @param {number} rotation orientation in radians
   * @returns {string} one filled path element
   */
  function plate(cx, cy, radius, rotation) {
    return '<path d="' + hexPath(cx, cy, radius, rotation) + '"/>';
  }

  /**
   * Draws one arm of a stellar dendrite up the +Y axis: a tapered trunk with
   * mirrored side branches, those branches' own second-order branches, growth
   * rings across the trunk, and a styled tip.
   *
   * The branches are mirrored, which makes the arm bilaterally symmetric, and the
   * caller then replicates the arm six times about the centre. That pair of steps
   * is the whole symmetry story: a six-fold (D6) result is impossible to get
   * wrong by construction, rather than a property of the numbers chosen.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armDendrite(rnd, radius) {
    const parts = [];
    const trunk = radius * (0.84 + rnd() * 0.16);
    const base = radius * (0.03 + rnd() * 0.022);
    parts.push(spike(0, 0, 0, trunk, base, base * 0.18));

    const branches = 3 + Math.floor(rnd() * 3);
    for (let i = 0; i < branches; i++) {
      const f = (i + 0.65) / (branches + 0.5);
      const at = trunk * f;
      const spread = (50 + rnd() * 18) * Math.PI / 180;
      const len = (trunk - at) * (0.5 + rnd() * 0.42);
      const half = base * (1 - f * 0.6) * (0.75 + rnd() * 0.35);
      for (const side of [-1, 1]) {
        const angle = side * spread;
        parts.push(spike(0, -at, angle, len, half, 0));
        const tipX = Math.sin(angle) * len;
        const tipY = -at - Math.cos(angle) * len;
        // second-order branching, which is what makes a crystal dendritic
        if (rnd() < 0.6) {
          const branchLen = len * (0.34 + rnd() * 0.26);
          const branchAngle = angle + side * (0.5 + rnd() * 0.45) * Math.PI / 3 * 0.9;
          parts.push(spike(tipX, tipY, branchAngle, branchLen, half * 0.55, 0));
        }
        // a plate capping a branch tip
        if (rnd() < 0.35) {
          parts.push(stellate(tipX, tipY, Math.max(1.5, len * (0.11 + rnd() * 0.08)), rnd(), rnd));
        }
      }
    }

    // the growth rings across the trunk that a real crystal leaves behind
    if (rnd() < 0.7) {
      const rings = 1 + Math.floor(rnd() * 2);
      for (let i = 0; i < rings; i++) {
        const at = trunk * ((i + 0.85) / (rings + 1.2));
        const half = base * (1 - at / trunk) * (1.9 + rnd() * 1.1);
        parts.push(bar(-half, -at - base * 0.3, half * 2, base * 0.62));
      }
    }

    // tip style: forked, capped with a plate, or a plain point
    const style = rnd();
    if (style < 0.4) {
      const fork = trunk * 0.17;
      const gap = trunk * 0.05;
      parts.push(spike(-gap, -trunk * 0.94, -0.16, fork, base * 0.3, 0));
      parts.push(spike(gap, -trunk * 0.94, 0.16, fork, base * 0.3, 0));
    } else if (style < 0.6) {
      parts.push(stellate(0, -trunk, base * 1.9, rnd(), rnd));
    }
    return parts;
  }

  /**
   * Builds one filled circular dot.
   *
   * Written as a path rather than a circle element so that every element of every
   * flake is a path: the worker rasterizes its own atlas straight out of the path
   * data it generated, and one element kind is what makes that a five-line parse
   * rather than a markup reader.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius dot radius in shape units
   * @returns {string} one filled path element
   */
  function dot(cx, cy, radius) {
    const twist = warpAt(cx, cy);
    if (twist) {
      const at = warpPoint(cx, cy, twist);
      cx = at[0];
      cy = at[1];
    }
    const r = coord(radius);
    const left = coord(cx - radius);
    const right = coord(cx + radius);
    const y = coord(cy);
    return '<path d="M' + left + ' ' + y + 'A' + r + ' ' + r + ' 0 1 0 ' + right + ' ' + y +
           'A' + r + ' ' + r + ' 0 1 0 ' + left + ' ' + y + 'Z"/>';
  }

  /**
   * Builds one filled axis-aligned bar. A path rather than a rect element, for the
   * same reason {@link dot} is: one element kind across the whole flake.
   *
   * @param {number} x left edge in shape units
   * @param {number} y top edge in shape units
   * @param {number} w width in shape units
   * @param {number} h height in shape units
   * @returns {string} one filled path element
   */
  function bar(x, y, w, h) {
    return '<path d="M' + coord(x) + ' ' + coord(y) + "L" + coord(x + w) + " " + coord(y) +
           "L" + coord(x + w) + " " + coord(y + h) + "L" + coord(x) + " " + coord(y + h) + 'Z"/>';
  }

  /**
   * Builds one filled wedge of a ring: the band between two radii across one arm's
   * share of the circle.
   *
   * Replicated six times with a half-angle of 30 degrees this closes into a
   * hexagonal ring, which is how a ring flake gets a hole in the middle without a
   * fill-rule hole: the gap is the space between elements, exactly as the space
   * between a dendrite's arms is.
   *
   * @param {number} centre distance from the flake's centre to the band's inner edge
   * @param {number} thickness radial thickness of the band
   * @param {number} halfAngle half of the wedge's angular width, in radians
   * @returns {string} one filled path element
   */
  function band(centre, thickness, halfAngle) {
    const inner = centre;
    const outer = centre + thickness;
    let d = "";
    for (const [r, a] of [[inner, -halfAngle], [outer, -halfAngle],
                          [outer, halfAngle], [inner, halfAngle]]) {
      d += (d ? "L" : "M") + coord(Math.sin(a) * r) + " " + coord(-Math.cos(a) * r);
    }
    return '<path d="' + d + 'Z"/>';
  }

  /**
   * Builds one filled star.
   *
   * @param {number} outer distance from centre to a point
   * @param {number} inner distance from centre to a notch
   * @param {number} points number of points, each spanning 180/points degrees
   * @param {number} rotation orientation in radians
   * @param {number} [cx] centre x in shape units, 0 for the flake's own centre
   * @param {number} [cy] centre y in shape units, 0 for the flake's own centre
   * @returns {string} one filled path element
   */
  function star(outer, inner, points, rotation, cx, cy) {
    const x0 = cx || 0;
    const y0 = cy || 0;
    let d = "";
    for (let k = 0; k < points * 2; k++) {
      const a = rotation + k * Math.PI / points;
      const r = k % 2 ? inner : outer;
      d += (k ? "L" : "M") + coord(x0 + Math.sin(a) * r) + " " + coord(y0 - Math.cos(a) * r);
    }
    return '<path d="' + d + 'Z"/>';
  }

  /**
   * Builds one stellated plate: a hexagon whose faces are pushed out into points.
   *
   * A plain hexagon is the one shape here that reads as a geometric primitive
   * rather than as a snowflake, and six copies of a fat one merge into a single
   * solid hexagon. Pushing the faces out keeps the six-fold outline and gives the
   * silhouette arms of its own, so a capped or ringed flake still reads as a
   * crystal.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius distance from centre to a point
   * @param {number} rotation orientation in radians
   * @param {function(): number} rnd source of randomness for the notch depth
   * @returns {string} one filled path element
   */
  function stellate(cx, cy, radius, rotation, rnd) {
    return star(radius, radius * (0.45 + rnd() * 0.2), 6, rotation, cx, cy);
  }

  /**
   * Builds one arm of a needle: a bare tapered trunk, sometimes plated partway
   * down and sometimes capped with a diamond.
   *
   * The six plain spokes of the reference sheets, and the cheapest crystal here at
   * two elements. What it trades for that is silhouette: with nothing branching,
   * it is the one habit that still reads as six separate arms at the 11-16 pixels
   * a flake is shown at, where a dendrite has already collapsed into a blob.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armNeedle(rnd, radius) {
    const trunk = radius * (0.88 + rnd() * 0.12);
    const base = radius * (0.05 + rnd() * 0.026);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.3)];
    // Barbs, not a bare stick. A real needle is a columnar crystal and the
    // reference sheets draw them with a few short side spurs; without them the
    // habit reads at 12 pixels as a spoke from a wheel rather than as a flake.
    const barbs = 2 + Math.floor(rnd() * 3);
    for (let i = 0; i < barbs; i++) {
      const at = trunk * ((i + 0.7) / (barbs + 0.6));
      const len = (trunk - at) * (0.3 + rnd() * 0.22);
      const half = base * (0.8 + rnd() * 0.5);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * (0.6 + rnd() * 0.35), len, half, 0));
      }
    }
    // Inter-arm web: a chord out to the point of the neighbouring arm at this
    // radius, so six copies close into a hexagonal web. Six separate spokes with
    // nothing between them is the one thing that makes this habit read as a wheel
    // rather than as a crystal.
    const web = trunk * (0.42 + rnd() * 0.26);
    parts.push(spike(0, -web, 2 * Math.PI / 3, web, base * 0.55, 0));
    if (rnd() < 0.6) {
      parts.push(stellate(0, -trunk * (0.5 + rnd() * 0.3), base * (1.9 + rnd() * 0.8), rnd() * Math.PI / 3, rnd));
    }
    if (rnd() < 0.5) {
      parts.push('<path d="' + ngonPath(0, -trunk, base * 2.4, 4, Math.PI / 4) + '"/>');
    }
    return parts;
  }

  /**
   * Builds one arm of a sectored plate: a trunk carrying stacked chevrons rather
   * than branches.
   *
   * A real sectored plate is a solid plate with ridges along the sector edges, and
   * at display size the ridges are the only part of it that survives. Stacking
   * chevrons along the trunk is the same silhouette built out of the same filled
   * spikes everything else here uses.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armSectored(rnd, radius) {
    const trunk = radius * (0.86 + rnd() * 0.14);
    const base = radius * (0.05 + rnd() * 0.02);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.25)];
    const chevrons = 3 + Math.floor(rnd() * 3);
    for (let i = 0; i < chevrons; i++) {
      const at = trunk * ((i + 0.5) / (chevrons + 0.4));
      const len = (trunk - at) * (0.55 + rnd() * 0.3);
      const half = base * (1.6 + rnd() * 0.9) * (1 - at / trunk * 0.3);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * (0.72 + rnd() * 0.2), len, half, 0));
      }
    }
    return parts;
  }

  /**
   * Builds one arm of a capped column: a trunk with flat hexagons stacked along it.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armCapped(rnd, radius) {
    const trunk = radius * (0.8 + rnd() * 0.15);
    const base = radius * (0.045 + rnd() * 0.02);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.35)];
    const caps = 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < caps; i++) {
      const at = trunk * ((i + 1) / (caps + 0.5));
      parts.push(stellate(0, -at, base * (2.1 + rnd() * 1.6) * (1 - at / trunk * 0.2), rnd() * Math.PI / 3, rnd));
    }
    parts.push(stellate(0, -trunk, base * (2.8 + rnd() * 1.6), rnd() * Math.PI / 3, rnd));
    return parts;
  }

  /**
   * Builds one arm of a beaded trunk: a thin spine with dots along it and a larger
   * dot on the end.
   *
   * The dots are at the edge of what survives the atlas downsample — about five
   * shape units is the floor, and these are four to seven — which is why this habit
   * is weighted low rather than treated as one of the strong reads.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armBeaded(rnd, radius) {
    const trunk = radius * (0.82 + rnd() * 0.18);
    const base = radius * 0.03;
    const parts = [spike(0, 0, 0, trunk, base, base * 0.4)];
    const beads = 3 + Math.floor(rnd() * 3);
    for (let i = 0; i < beads; i++) {
      const at = trunk * ((i + 0.8) / (beads + 0.6));
      parts.push(dot(0, -at, base * (1.4 + rnd() * 1.2) * (1 - at / trunk * 0.25)));
    }
    parts.push(dot(0, -trunk, base * (2.4 + rnd() * 1.4)));
    return parts;
  }

  /**
   * Builds one arm of a ring flake: a spoke out to a hexagonal band, and a short
   * spur beyond it.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armRinged(rnd, radius) {
    const trunk = radius * (0.9 + rnd() * 0.1);
    const base = radius * (0.035 + rnd() * 0.015);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.25)];
    const at = trunk * (0.42 + rnd() * 0.2);
    const thick = base * (2.2 + rnd() * 1.2);
    parts.push(band(at, thick, Math.PI / 6));
    // Stellated, and exaggeratedly so: a bare hexagonal band reads as a geometric
    // primitive, so the corners of each arm's wedge throw long thin points out
    // radially and short ones in, and six copies give the ring twelve of each.
    for (const side of [-1, 1]) {
      const a = side * Math.PI / 6;
      const px = Math.sin(a) * at;
      const py = -Math.cos(a) * at;
      parts.push(spike(px, py, a, thick * 3.6, base * 1.05, 0));
      parts.push(spike(px, py, a + Math.PI, thick * 1.3, base * 0.8, 0));
    }
    if (rnd() < 0.7) {
      parts.push(stellate(0, -trunk, base * (2.4 + rnd() * 1.2), rnd() * Math.PI / 3, rnd));
    }
    return parts;
  }

  /**
   * Builds one arm of a star-cored flake: a solid six-point star at the centre, a
   * long spoke, and one chevron partway out.
   *
   * The core is drawn once per arm and the six copies overlap exactly, so the star
   * costs one element and reads as a solid centre rather than as six wedges.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armStar(rnd, radius) {
    const core = radius * (0.3 + rnd() * 0.12);
    // Two stars, one turned through 30 degrees, so the outline is notched twice
    // around rather than once: that overlap is what reads as faceting. A single fat
    // six-point star reads as a cookie cutter.
    const parts = [
      star(core, core * (0.3 + rnd() * 0.12), 6, 0),
      star(core * 0.72, core * (0.26 + rnd() * 0.1), 6, Math.PI / 6)
    ];
    const trunk = radius * (0.9 + rnd() * 0.1);
    const base = radius * 0.03;
    parts.push(spike(0, 0, 0, trunk, base, base * 0.2));
    // Barbs along the spoke, so the arms are crystals rather than wedges.
    const barbs = 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < barbs; i++) {
      const at = trunk * ((i + 0.8) / (barbs + 0.7));
      const len = (trunk - at) * (0.3 + rnd() * 0.2);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * (0.5 + rnd() * 0.2), len, base * 0.85, 0));
      }
    }
    parts.push(stellate(0, -trunk, base * (2.2 + rnd() * 1.2), rnd() * Math.PI / 3, rnd));
    return parts;
  }

  /**
   * The twelve vertices of an icosahedron of edge 2, which are also the twelve
   * vertices of the small stellated dodecahedron built on it.
   */
  const ICOSA = (() => {
    const p = (1 + Math.sqrt(5)) / 2;
    const v = [];
    for (const a of [-1, 1]) {
      for (const b of [-1, 1]) {
        v.push([0, a, b * p], [a, b * p, 0], [a * p, 0, b]);
      }
    }
    return v;
  })();

  /**
   * The edges of a small stellated dodecahedron, as index pairs into {@link ICOSA}.
   *
   * The twelve vertices are shared with an icosahedron and the two solids differ
   * only in which pairs are joined: distance 2 is an icosahedron edge, distance 2φ
   * is a stellated one. Only the stellated set is drawn. Joining both, as an earlier
   * version did, puts sixty lines through the middle of a sprite that is shown
   * sixteen pixels across, and the centre of the flake turns to mush.
   */
  const WIRE = (() => {
    const p = (1 + Math.sqrt(5)) / 2;
    const stellated = [];
    for (let i = 0; i < ICOSA.length; i++) {
      for (let j = i + 1; j < ICOSA.length; j++) {
        const dx = ICOSA[i][0] - ICOSA[j][0];
        const dy = ICOSA[i][1] - ICOSA[j][1];
        const dz = ICOSA[i][2] - ICOSA[j][2];
        if (Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - 2 * p) < 1e-9) {stellated.push([i, j]);}
      }
    }
    return {stellated};
  })();

  /**
   * Rotates a point about an arbitrary axis through the origin (Rodrigues).
   *
   * @param {number[]} v the point
   * @param {number[]} u a unit axis
   * @param {number} a angle in radians
   * @returns {number[]} the rotated point
   */
  function rotAxis(v, u, a) {
    const c = Math.cos(a);
    const s = Math.sin(a);
    const d = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
    return [
      v[0] * c + (u[1] * v[2] - u[2] * v[1]) * s + u[0] * d * (1 - c),
      v[1] * c + (u[2] * v[0] - u[0] * v[2]) * s + u[1] * d * (1 - c),
      v[2] * c + (u[0] * v[1] - u[1] * v[0]) * s + u[2] * d * (1 - c)
    ];
  }

  /**
   * Builds one frame of a stellated dodecahedron: the same solid seen from a
   * different orientation, projected flat.
   *
   * This is the one habit that is not a flat crystal, and it is drawn as strokes
   * rather than the filled spikes everything else uses, because a wireframe is what
   * a stellated polyhedron is. The strokes are wide enough to survive the atlas
   * downsample — see {@link WIRE_STROKE_NEAR}.
   *
   * Each frame is a step of a rotation about an oblique axis, so the tumble has
   * all three coordinates in it; the flake's own spin drives which frame is shown,
   * which is what makes it read as a three-dimensional turn rather than as a flip
   * book.
   *
   * @param {function(): number} rnd source of randomness, identical across a
   *   slot's frames so only the orientation changes
   * @param {number} radius distance from the centre to the edge of the drawing
   * @param {number} frame which step of the tumble to draw
   * @param {number} frames steps in a full turn
   * @returns {string[]} the frame's path elements
   */
  function armDodeca(rnd, radius, frame, frames) {
    const f = frame || 0;
    const n = Math.max(1, frames || FRAMES);
    let ux = rnd() * 1.6 - 0.8;
    let uy = rnd() * 1.6 - 0.8;
    let uz = 1 + rnd();
    const un = Math.sqrt(ux * ux + uy * uy + uz * uz);
    ux /= un;
    uy /= un;
    uz /= un;
    const tilt = 0.3 + rnd() * 0.8;
    const scale = radius * (0.72 + rnd() * 0.12) / Math.sqrt(1 + ((1 + Math.sqrt(5)) / 2) ** 2);

    const at = [];
    for (const v of ICOSA) {
      let p = rotAxis(v, [ux, uy, uz], f * 2 * Math.PI / n);
      // a fixed lean about x, so the axis is never square to the viewer
      const y = p[1] * Math.cos(tilt) - p[2] * Math.sin(tilt);
      const z = p[1] * Math.sin(tilt) + p[2] * Math.cos(tilt);
      // a little perspective: nearer points are pushed out, and it is the change in
      // the outline between frames, more than the size, that reads as depth
      const depth = 1 + z * 0.11 / scale;
      at.push([p[0] * scale * depth, -y * scale * depth, z]);
    }

    const groups = [[], []];
    for (const [i, j] of WIRE.stellated) {groups[zNear(at, i, j) ? 0 : 1].push([at[i], at[j]]);}
    // The twelve points, sharpened: each vertex throws a short spike straight out,
    // which is what makes the silhouette read as a stellation rather than as a ball
    // of crossing lines.
    for (const v of at) {
      groups[v[2] > 0 ? 0 : 1].push([v, [v[0] * 1.2, v[1] * 1.2, v[2]]]);
    }
    const widths = [WIRE_STROKE_NEAR, WIRE_STROKE_FAR];
    const parts = [];
    for (let g = 0; g < groups.length; g++) {
      if (groups[g].length === 0) {continue;}
      let d = "";
      for (const [a, b] of groups[g]) {
        d += "M" + coord(a[0]) + " " + coord(a[1]) + "L" + coord(b[0]) + " " + coord(b[1]);
      }
      parts.push('<path d="' + d + '" fill="none" stroke=":color:" stroke-width="' +
                 coord(widths[g]) + '" stroke-linecap="round"/>');
    }
    return parts;
  }

  /**
   * Whether an edge sits on the near side of the solid.
   *
   * @param {number[][]} at the projected vertices, with z still attached
   * @param {number} i first vertex index
   * @param {number} j second vertex index
   * @returns {boolean} true when the edge's midpoint is toward the viewer
   */
  function zNear(at, i, j) {
    return (at[i][2] + at[j][2]) / 2 > 0;
  }

  /**
   * The arm builders, with the weight each is drawn at.
   *
   * A dendrite is what snow reads as, so it stays the most common; the rest are
   * there to break up a field of twenty identical silhouettes. Weighted by how
   * well each one still reads at the 11-16 pixels a flake is shown at, measured
   * against a contact sheet of each habit at the atlas cell size and at those two:
   * the needle, sectored, capped and star habits are still legible at 12 pixels,
   * the beaded habit is marginal, and a fine-needle burst and a faceted plate were
   * dropped for collapsing into a blob.
   */
  const HABITS = [
    ["dendrite", 28, armDendrite, false],
    ["needle", 13, armNeedle, false],
    ["sectored", 13, armSectored, false],
    ["capped", 11, armCapped, false],
    ["ringed", 9, armRinged, false],
    ["star", 11, armStar, false],
    ["beaded", 7, armBeaded, false],
    ["dodeca", 8, armDodeca, true]
  ];

  /**
   * Picks an arm builder from {@link HABITS} by weight.
   *
   * @param {function(): number} rnd source of randomness
   * @returns {{name: string, build: function(function(): number, number, number=, number=): string[], wire: boolean}} the habit
   */
  function pickHabit(rnd) {
    let total = 0;
    for (const habit of HABITS) {total += habit[1];}
    let roll = rnd() * total;
    for (const habit of HABITS) {
      roll -= habit[1];
      if (roll < 0) {return {name: habit[0], build: habit[2], wire: habit[3]};}
    }
    return {name: HABITS[0][0], build: HABITS[0][2], wire: HABITS[0][3]};
  }

  /**
   * Assembles one whole flake from the arm replicated six times about the centre.
   *
   * The arm is defined once in a defs block and referenced with use elements,
   * which is what keeps the markup smaller than the hand-drawn files it replaces:
   * inlining the arm six times costs about 7.9 KB per flake against about 1.7 KB
   * this way. The fill sits on the referenced group itself, not on a wrapper
   * around it: a use element deep-clones its target, and the clone inherits from
   * the use rather than from the target's original parent, so a fill on a defs
   * wrapper colours the markup and nothing else.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} size edge length of the square the flake fills
   * @param {{name: string, build: Function, wire: boolean}} [habit]
   *   arm builder to use, drawn by weight from {@link HABITS} when not given
   * @param {number} [frame] which of the slot's frames this is
   * @param {number} [frames] frames per slot
   * @returns {string} a complete SVG document
   */
  function buildShape(rnd, size, habit, frame, frames) {
    const radius = size / 2 - 1;
    const picked = habit || pickHabit(rnd);
    const n = Math.max(1, frames || FRAMES);
    const f = Math.max(0, Math.min(n - 1, frame || 0));
    // A flat crystal's frames differ only in where in the warp cycle they sit, so
    // the twist walks through zero and back: the flake untwists, then re-twists the
    // other way. The wireframe habit has no warp — its frames are orientations.
    if (picked.wire) {
      warp = null;
    } else if (rnd() < WARP_CHANCE) {
      const amp = (rnd() - 0.5) * 2 * WARP_MAX;
      const phase = rnd() * Math.PI * 2;
      warp = {k: amp * Math.sin(phase + f * 2 * Math.PI / n), radius: radius};
    } else {
      warp = null;
    }
    const arm = picked.build(rnd, radius, f, n).join("");
    warp = null;
    const core = !picked.wire && rnd() < 0.75
      ? stellate(0, 0, radius * (0.1 + rnd() * 0.12), rnd() * Math.PI / 3, rnd)
      : "";
    const half = size / 2;
    let uses = "";
    // A wireframe is not six-fold symmetric, so it is drawn once rather than
    // replicated; the replication count is part of the habit, not a guess here.
    const copies = picked.wire ? 1 : 6;
    for (let k = 0; k < copies; k++) {
      uses += '<use xlink:href="#a"' + (k ? ' transform="rotate(' + (k * 60) + ')"' : "") + "/>";
    }
    return '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" ' +
           'width="' + size + '" height="' + size + '" ' +
           'viewBox="' + coord(-half) + " " + coord(-half) + " " + size + " " + size + '">' +
           '<defs><g id="a" fill=":color:">' + core + arm + "</g></defs>" + uses + "</svg>";
  }

  /**
   * Generates {@link FRAMES} shapes per flake slot.
   *
   * This replaces the six hand-drawn files as the shape source: nothing is
   * fetched, and every flake in the field is a different crystal, which costs
   * nothing at draw time because the atlas already holds one cell per frame.
   * Generation runs once at startup, on the worker thread.
   *
   * The first pass deals one of each habit in a shuffled order, so a field of ten
   * still shows every silhouette rather than whatever seven weighted draws happened
   * to land on. Past that the habit is drawn by weight.
   *
   * A slot's frames are built from the same seed, so the crystal is identical
   * across them and only the warp (or the orientation) changes.
   *
   * @param {number} count number of flake slots
   * @param {function(): number} rnd source of randomness
   * @returns {string[]} count * FRAMES complete SVG documents, slot-major
   */
  function buildSlots(count, rnd) {
    const deck = HABITS.map(habit => ({name: habit[0], build: habit[2], wire: habit[3]}));
    for (let i = deck.length - 1; i > 0; i--) {
      const j = Math.floor(rnd() * (i + 1));
      const swap = deck[i];
      deck[i] = deck[j];
      deck[j] = swap;
    }
    const shapes = [];
    const wire = [];
    for (let i = 0; i < count; i++) {
      const seed = Math.floor(rnd() * 0x7fffffff);
      const habit = i < deck.length ? deck[i] : pickHabit(prng(seed));
      wire.push(habit.wire);
      for (let f = 0; f < FRAMES; f++) {
        shapes.push(buildShape(prng(seed), SHAPE_VIEW, habit, f, FRAMES));
      }
    }
    return {shapes, wire};
  }

  /**
   * Generates {@link FRAMES} shapes per flake slot.
   *
   * @param {number} count number of flake slots
   * @param {function(): number} rnd source of randomness
   * @returns {string[]} count * FRAMES complete SVG documents, slot-major
   */
  function buildShapes(count, rnd) {
    return buildSlots(count, rnd).shapes;
  }

  /**
   * Creates one flake in a random position inside {@link bounds}, taking its
   * shape and colour from the atlas slot at {@link cell}.
   *
   * @param {Object} bounds {width, height} of the viewport in CSS pixels
   * @param {number} cell atlas slot this flake draws from
   * @param {function(): number} rnd source of randomness
   * @param {boolean} [wire] true when this slot is a wireframe habit, whose frames
   *   are orientations rather than warp steps
   * @returns {Object} the flake's mutable state
   */
  function createFlake(bounds, cell, rnd, wire) {
    const size = Math.max(11, rnd() * 9 + 7);
    const flake = {
      cell,
      initialSize: size,
      size,
      x: rnd() * bounds.width,
      y: rnd() * bounds.height - size,
      speed: 0.3 + (size / 17) * 0.6,
      vy: 0.3 + (size / 17) * 0.6,
      vx: 0,
      initialOpacity: rnd() * 0.2 + 0.8,
      opacity: 0,
      opacityPhase: rnd() * Math.PI * 2,
      opacitySpeed: rnd() * 0.075 + 0.015,
      rotation: rnd() * Math.PI * 3,
      // Both directions, so a field of flakes does not read as one pinwheel. The
      // sign is the flake's own and the death spin inherits it; spinSpeed is what
      // keeps the magnitude off zero rather than this clamp keeping it positive.
      rotationSpeed: (rnd() - 0.5) * 0.12,
      windPhase: rnd() * Math.PI * 2,
      windSpeed: WIND_SPEEDS[Math.floor(rnd() * WIND_SPEEDS.length)],
      totalDistance: 0,
      maxDistance: bounds.height + size,
      isDying: false,
      deathSpinSpeed: 0,
      deathFallSpeed: 0,
      deathAge: 0,
      // Sideways rate a dying flake keeps from the sway it was in when it let go.
      driftVx: 0,
      // The death spin's envelope and wobble: spinEnv is the signed rate the wobble
      // is measured against, and the spin itself is spinEnv * (bias + wobble).
      spinEnv: 0,
      wobblePhase: 0,
      wobbleRate: 0,
      wobbleAmp: 1,
      spinBias: 0,
      // Separation impulses from nearby flakes, decayed by GUST_DECAY each step.
      gustVx: 0,
      gustVy: 0,
      gustSpin: 0,
      // Which of the slot's frames is showing, and how long it has been held.
      wire: !!wire,
      frame: 0,
      steps: 0,
      // Where in its slot's orientation cycle this flake starts, so a field of
      // wireframes is not sixteen copies of the same face.
      tumblePhase: Math.floor(rnd() * FRAMES)
    };
    flake.opacity = flake.initialOpacity;
    return flake;
  }

  /**
   * Resets a flake to the top of the viewport, keeping its size, colour and
   * drift character so a recycled flake is indistinguishable from a new one.
   *
   * @param {Object} flake the flake to recycle
   * @param {Object} bounds {width, height} of the viewport in CSS pixels
   * @param {function(): number} rnd source of randomness
   * @returns {void}
   */
  function respawn(flake, bounds, rnd) {
    flake.y = -flake.initialSize;
    flake.x = rnd() * bounds.width;
    flake.totalDistance = 0;
    flake.size = flake.initialSize;
    flake.opacity = flake.initialOpacity;
    flake.vy = flake.speed;
    flake.isDying = false;
    flake.deathSpinSpeed = 0;
    flake.deathFallSpeed = 0;
    flake.deathAge = 0;
    flake.spinEnv = 0;
    flake.gustVx = 0;
    flake.gustVy = 0;
    flake.gustSpin = 0;
  }

  /**
   * The angular velocity a living flake turns at, in signed radians per step.
   *
   * A flake that has shrunk turns faster than it did at full size, which is what
   * keeps a field of mixed sizes from reading as one rigid sheet. The floor is on
   * the magnitude rather than the value so that a flake which drew a near-zero
   * speed still turns, and a flake drawing a negative one still turns the other
   * way — the sign is the flake's own, and the death spin has to inherit it or the
   * spiral reads as a reversal.
   *
   * @param {Object} flake the flake to read
   * @returns {number} signed radians per step, never nearer zero than 0.01
   */
  function spinSpeed(flake) {
    const speed = flake.rotationSpeed * Math.max(1, flake.initialSize / flake.size);
    return speed < 0 ? Math.min(-0.01, speed) : Math.max(0.01, speed);
  }

  /**
   * Chooses the death spin for a flake that has just started to fall out.
   *
   * The spin is the sum of a steady part and a wobble, both scaled by an envelope
   * that starts at the rate the flake was already turning at:
   *
   *     spin = spinEnv * (spinBias + wobbleAmp * sin(wobblePhase))
   *
   * The wobble is what makes the fall-out read as a tumbling flake rather than as a
   * drill. Because its amplitude is always at least large enough to cancel the
   * steady part, the total passes through zero twice a period — the flake slows,
   * stalls, and comes back the other way, which is what a real flake does when the
   * air catches one face. A reversal through zero is smooth by construction; the
   * old model's jump from a fixed 0.5 to whatever came next was a snap, and a
   * random sign flip on a fast spin would have been a worse one.
   *
   * The factor in brackets is pinned to 1 on the first step, so the spiral begins
   * at exactly the rate and in the direction the flake was already turning.
   *
   * @param {Object} flake the flake that just shrank past the removal size
   * @param {function(): number} rnd source of randomness
   * @returns {void}
   */
  function seedDeathSpin(flake, rnd) {
    flake.spinEnv = spinSpeed(flake);
    flake.wobbleRate = DEATH_WOBBLE_RATE_MIN +
                       rnd() * (DEATH_WOBBLE_RATE_MAX - DEATH_WOBBLE_RATE_MIN);
    // Most flakes keep the direction they were turning in and merely stall out of
    // it; a quarter bias against it, so the fall-out visibly reverses them.
    const bias = DEATH_SPIN_BIAS * (rnd() < DEATH_SPIN_REVERSE ? -1 : 1);
    const minAmp = Math.max(1, 1 - bias);
    const amp = minAmp + rnd() * (DEATH_WOBBLE_MAX - minAmp);
    flake.spinBias = bias;
    flake.wobbleAmp = amp;
    flake.wobblePhase = Math.asin(Math.min(1, (1 - bias) / amp));
  }

  /**
   * The angular velocity a dying flake turns at this step, in signed radians.
   *
   * Advances the envelope and the wobble phase, so call it exactly once a step.
   *
   * @param {Object} flake the dying flake to read
   * @returns {number} signed radians per step, bounded by {@link DEATH_SPIN_MAX}
   *   times the wobble factor
   */
  function deathSpinAt(flake) {
    const sign = flake.spinEnv < 0 ? -1 : 1;
    flake.spinEnv = sign * Math.min(DEATH_SPIN_MAX, Math.abs(flake.spinEnv) * DEATH_SPIN_GROWTH);
    const spin = flake.spinEnv * (flake.spinBias + flake.wobbleAmp * Math.sin(flake.wobblePhase));
    flake.wobblePhase += flake.wobbleRate;
    return spin;
  }

  /**
   * Advances one flake by a single fixed step: drift, wind that strengthens as
   * the flake ages, the size and opacity envelope over its travel, the sparkle
   * flicker, and the death spin once it has shrunk past the removal threshold.
   *
   * The step count, not the clock, drives the death spin, so the effect is the
   * same whatever the display refresh rate.
   *
   * @param {Object} flake the flake to advance
   * @param {Object} bounds {width, height} of the viewport in CSS pixels
   * @param {function(): number} rnd source of randomness for the sparkle flicker
   * @returns {void}
   */
  function stepFlake(flake, bounds, rnd) {
    if (flake.isDying && flake.deathAge >= DEATH_SPIN_STEPS) {
      flake.deathFallSpeed += DEATH_FALL_ACCEL;
      flake.vy = flake.deathFallSpeed;
    }

    if (flake.isDying) {
      // A living flake's sideways motion is a sway read straight off a phase, with
      // no momentum behind it, so the old fall-out simply stopped moving sideways
      // and the flake dropped straight down from wherever the sway had left it.
      // Keeping the rate it had at the moment it let go, and shedding it gradually,
      // makes the fall-out leave on the heading the flake was already on.
      flake.driftVx *= DEATH_DRIFT_DECAY;
      flake.deathSpinSpeed = deathSpinAt(flake);
      flake.rotation += flake.deathSpinSpeed + flake.gustSpin;
      // A flake tumbling fast does not fall along a straight line: a little of the
      // spin comes back out as sideways travel, which is what makes the path read
      // as a spiral rather than as a spin in place.
      flake.x += flake.driftVx +
                 Math.sin(flake.rotation) * flake.deathSpinSpeed * SPIN_LATERAL + flake.gustVx;
    } else {
      flake.windPhase += WIND_STEP;
      flake.vx = Math.sin(flake.windPhase) * flake.windSpeed;
      flake.driftVx = flake.vx;
      flake.rotation += spinSpeed(flake) + flake.gustSpin;
      flake.x += flake.vx + flake.gustVx;
    }
    flake.y += flake.vy + flake.gustVy;
    flake.totalDistance += flake.vy;

    // Which frame of the slot's strip is showing. A flat crystal walks its warp
    // cycle on a slow timer; a wireframe walks its orientation cycle, from its own
    // starting angle, about the axis its slot was built with.
    flake.steps++;
    flake.frame = flake.wire
      ? (flake.tumblePhase + Math.floor(flake.steps / TUMBLE_BEATS)) % FRAMES
      : Math.floor(flake.steps / FRAME_BEATS) % FRAMES;

    const travelled = Math.min(1, flake.totalDistance / flake.maxDistance);
    const windIndex = travelled * (WIND_SPEEDS.length - 1);
    const lower = Math.floor(windIndex);
    const upper = Math.min(lower + 1, WIND_SPEEDS.length - 1);
    const target = WIND_SPEEDS[lower] + (WIND_SPEEDS[upper] - WIND_SPEEDS[lower]) * (windIndex - lower);
    flake.windSpeed += (target - flake.windSpeed) * 0.05;

    // One smooth curve over the flake's travel rather than four straight segments.
    // The piecewise form this replaces changes slope at each quarter, and a slope
    // change reads as a jerk even where the value is continuous; the Math.max floors
    // inside it also let a flicker flatten the curve and then release it, which was
    // the other half of the pulse.
    //
    // Eased in, not through the middle: a flake holds its size for most of the fall
    // and melts away at the end of it. A curve that does its work early — a
    // smoothstep reaches 0.8 at 72% of the travel — crosses the removal size
    // mid-screen, and a flake that vanishes in mid-air and reappears bright at the
    // top is what reads here as one pulsing on and off.
    const ease = travelled * travelled;
    flake.size = flake.initialSize - (flake.initialSize - REMOVAL_SIZE + 0.4) * ease;
    // And dim by three tenths over the journey, not by more than that: past this the
    // fade-out to a respawn is a blink, not ageing.
    flake.opacity = flake.initialOpacity * (1 - 0.3 * travelled);

    // The flicker rides on top of the envelope as a bounded multiplier, so it can
    // never move the size or push the opacity across a threshold of its own.
    flake.opacityPhase += flake.opacitySpeed;
    flake.opacity *= 0.95 + Math.sin(flake.opacityPhase) * 0.05 + (rnd() - 0.5) * 0.03;

    if (flake.size < REMOVAL_SIZE) {
      if (!flake.isDying) {
        flake.isDying = true;
        seedDeathSpin(flake, rnd);
        // The rate the next step turns at, computed now so a reader of the flake
        // state sees the spiral's starting rate on the step the spiral starts.
        flake.deathSpinSpeed = spinSpeed(flake);
        flake.deathFallSpeed = flake.vy;
        flake.deathAge = 0;
      }
      flake.deathAge++;
      if (flake.y > bounds.height + flake.size) {
        respawn(flake, bounds, rnd);
        return;
      }
    }

    if (flake.x > bounds.width + flake.size) {flake.x = -flake.size;}
    else if (flake.x < -flake.size) {flake.x = bounds.width + flake.size;}

    if (flake.y > bounds.height + flake.size) {
      respawn(flake, bounds, rnd);
      flake.y = -flake.size;
    }
  }

  /**
   * Packs one flake into the frame buffer as a draw transform: scale and rotation
   * are baked into the 2x3 matrix, and the translation is in device pixels, so
   * the page paints a flake with one setTransform and one drawImage and does no
   * trigonometry of its own.
   *
   * @param {Object} flake the flake to pack
   * @param {Float32Array} buf destination buffer
   * @param {number} index flake index within the frame
   * @param {number} dpr device pixel ratio the page is painting at
   * @returns {void}
   */
  function writeFlake(flake, buf, index, dpr) {
    const at = index * STRIDE;
    const cos = Math.cos(flake.rotation) * flake.size * dpr;
    const sin = Math.sin(flake.rotation) * flake.size * dpr;
    buf[at + F_X] = flake.x * dpr;
    buf[at + F_Y] = flake.y * dpr;
    buf[at + F_A] = cos;
    buf[at + F_B] = sin;
    buf[at + F_C] = -sin;
    buf[at + F_D] = cos;
    buf[at + F_ALPHA] = Math.max(0, Math.min(1, flake.opacity));
    buf[at + F_CELL] = flake.cell * FRAMES + flake.frame;
  }

  /** The worker's live state: viewport, flakes, transport and frame cadence. */
  const state = {
    bounds: {width: 0, height: 0},
    dpr: 1,
    rnd: Math.random,
    palette: [],
    shapes: [],
    wire: [],
    flakes: [],
    count: 0,
    base: 0,
    offscreen: false,
    canvas: null,
    ctx: null,
    atlas: null,
    layout: null,
    running: false,
    prepared: false,
    accumulator: 0,
    last: 0,
    cadence: null,
    timer: null,
    buffer: null,
    frameLength: 0,
    dirtyRects: null,
    dirty: 0,
    dirtyAll: true,
    frames: 0,
    statsAt: 0,
    /** Paint divisor in force, 1 to {@link RATE_MAX}. */
    rate: 1,
    /** Beats of the loop since the last rate change, so the divisor realigns. */
    beat: 0
  };

  /**
   * Posts one message to the page.
   *
   * @param {Object} message the payload
   * @param {Array<ArrayBuffer>} [transfer] buffers to hand over rather than copy
   * @returns {void}
   */
  function send(message, transfer) {
    self.postMessage(message, transfer || []);
  }

  /**
   * Whether this worker can paint into a canvas the page transferred to it.
   * Probed rather than assumed: the API exists in some builds without a working
   * 2D context in a worker.
   *
   * @returns {boolean} true when offscreen painting is available
   */
  function offscreenAvailable() {
    if (typeof OffscreenCanvas !== "function") {return false;}
    try {
      const probe = new OffscreenCanvas(1, 1);
      return !!probe.getContext("2d");
    } catch {
      return false;
    }
  }

  /**
   * Sizes the offscreen canvas to the viewport at the page's pixel ratio.
   *
   * @returns {void}
   */
  function sizeCanvas() {
    if (!state.canvas) {return;}
    state.canvas.width = Math.max(1, Math.round(state.bounds.width * state.dpr));
    state.canvas.height = Math.max(1, Math.round(state.bounds.height * state.dpr));
  }

  /**
   * Reads the path data out of a shape this worker generated.
   *
   * Deliberately narrow rather than an SVG reader: the markup is ours, every
   * element of it is a path, and the only things that matter are the stroke widths
   * on a wireframe and the rotations on the replicated arm.
   *
   * @param {string} svg the shape's markup
   * @returns {{paths: Array<{d: string, w: number}>, uses: number[]}} the fills and
   *   strokes, and the arm rotations to draw them at
   */
  function shapePaths(svg) {
    const defs = /<defs><g id="a"[^>]*>([\s\S]*?)<\/g><\/defs>/.exec(svg);
    const body = defs ? defs[1] : "";
    const paths = [];
    const found = /<path d="([^"]*)"([^>]*)\/>/g;
    let hit;
    while ((hit = found.exec(body)) !== null) {
      const width = /stroke-width="([-\d.]+)"/.exec(hit[2]);
      paths.push({d: hit[1], w: width ? Number(width[1]) : 0});
    }
    const uses = [0];
    const arms = /<use [^>]*transform="rotate\((\d+)\)"/g;
    let arm;
    while ((arm = arms.exec(svg)) !== null) {uses.push(Number(arm[1]));}
    return {paths, uses};
  }

  /**
   * Rasterizes the generated shapes into the sprite atlas, on the worker thread.
   *
   * The page used to do this with an Image element per cell and hand the bitmap
   * over, which costs the main thread a decode per cell — and with a frame strip per
   * slot there are {@link FRAMES} times as many cells as there used to be. It is
   * also not available here at all: createImageBitmap does not decode SVG in this
   * browser (measured: "The source image could not be decoded"), and a worker has no
   * Image element. So the worker rasterizes the markup it wrote, with Path2D,
   * because the path data is already in its own hand. Measured at 1.3 ms for 120
   * cells of six filled rotations each.
   *
   * @returns {boolean} false when no canvas or context could be made, in which case
   *   the page's own rasterization is used instead
   */
  function rasterAtlas() {
    if (typeof OffscreenCanvas !== "function" || typeof Path2D !== "function") {return false;}
    let canvas;
    let ctx;
    try {
      canvas = new OffscreenCanvas(state.layout.width, state.layout.height);
      ctx = canvas.getContext("2d");
    } catch {
      return false;
    }
    if (!ctx) {return false;}
    const scale = CELL / SHAPE_VIEW;
    ctx.lineCap = "round";
    for (let i = 0; i < state.shapes.length; i++) {
      const color = state.palette[Math.floor(i / FRAMES) % state.palette.length].color;
      const shape = shapePaths(state.shapes[i]);
      ctx.save();
      ctx.translate((i % state.layout.cols) * CELL + CELL / 2,
                    Math.floor(i / state.layout.cols) * CELL + CELL / 2);
      ctx.scale(scale, scale);
      ctx.fillStyle = color;
      ctx.strokeStyle = color;
      for (const deg of shape.uses) {
        ctx.save();
        ctx.rotate(deg * Math.PI / 180);
        for (const part of shape.paths) {
          const path = new Path2D(part.d);
          if (part.w > 0) {
            ctx.lineWidth = part.w;
            ctx.stroke(path);
          } else {
            ctx.fill(path);
          }
        }
        ctx.restore();
      }
      ctx.restore();
    }
    state.atlas = canvas;
    return true;
  }

  /**
   * Rescales every flake's travel budget after a resize, so a flake that was
   * halfway down a taller window is not still dying at the old window's height.
   *
   * @returns {void}
   */
  function retargetTravel() {
    for (const flake of state.flakes) {
      flake.maxDistance = state.bounds.height + flake.size;
      if (flake.x > state.bounds.width + flake.size) {flake.x = state.bounds.width * state.rnd();}
      if (flake.y > state.bounds.height + flake.size) {flake.y = state.bounds.height * state.rnd();}
    }
  }

  /**
   * Sets the live flake count, adding or dropping flakes at the end of the list.
   * New flakes reuse an existing atlas slot, which is why the atlas never needs
   * rebuilding when the quality controller changes the count.
   *
   * @param {number} count flakes to run, never above the palette length
   * @returns {void}
   */
  function setCount(count) {
    const want = Math.max(1, Math.min(state.palette.length, count | 0));
    while (state.flakes.length > want) {state.flakes.pop();}
    while (state.flakes.length < want) {
      const slot = state.flakes.length % state.palette.length;
      state.flakes.push(createFlake(state.bounds, slot, state.rnd, state.wire[slot]));
    }
    state.count = want;
  }

  /**
   * Sets the paint divisor the page's quality controller has asked for.
   *
   * The beat counter is reset so the next beat is always drawn. Without that, a
   * divisor change landing just after a skipped beat would hold the previous frame
   * on screen for a whole extra beat, and on the way back up to a full rate that
   * reads as a hitch rather than as a recovery.
   *
   * @param {number} rate beats to skip between paints, clamped to
   *   [1, {@link RATE_MAX}]
   * @returns {boolean} true when the divisor actually changed
   */
  function setRate(rate) {
    const want = Math.max(1, Math.min(RATE_MAX, rate | 0));
    if (want === state.rate) {return false;}
    state.rate = want;
    state.beat = 0;
    return true;
  }

  /**
   * Nudges flakes that are about to run into each other apart.
   *
   * Twenty flakes in a viewport overlap constantly, and a field where nothing ever
   * interacts reads as a screensaver. This is the cheap version of the thing wind
   * actually does: a flake approaching another feels a push that grows as the gap
   * closes, and the push is accumulated into a gust velocity that decays over many
   * steps rather than applied as a position change, so a near-miss bends a path
   * instead of jumping one.
   *
   * Three things keep it from looking wrong:
   *
   *   - only pairs that are closing are touched. Two flakes falling side by side at
   *     the same speed have no relative motion and get nothing, which is what stops
   *     the whole field from jostling continuously;
   *   - the push is shared by mass, and mass goes with area, so the small flake of
   *     a pair is the one that gets blown aside;
   *   - an off-centre approach has a tangential component, and that goes into spin,
   *     so a graze sets a flake turning the way the wake would.
   *
   * The reach is deliberately generous and the push deliberately weak: flakes part
   * before they overlap, but a strong wind can still push two through each other,
   * which is what real ones do.
   *
   * Cost is one pass over the pairs, so 190 distance tests a step at the
   * {@link MAX_FLAKES} ceiling — noise next to the draw.
   *
   * @param {Object[]} flakes the live field
   * @returns {void}
   */
  function separateFlakes(flakes) {
    const n = flakes.length;
    for (let i = 0; i < n; i++) {
      const a = flakes[i];
      for (let j = i + 1; j < n; j++) {
        const b = flakes[j];
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const reach = (a.size + b.size) * SOFT_REACH;
        const d2 = dx * dx + dy * dy;
        if (d2 > reach * reach || d2 < 0.01) {continue;}
        const d = Math.sqrt(d2);
        const nx = dx / d;
        const ny = dy / d;
        const rvx = b.vx + b.gustVx - a.vx - a.gustVx;
        const rvy = b.vy + b.gustVy - a.vy - a.gustVy;
        if (rvx * nx + rvy * ny >= 0) {continue;}
        const near = 1 - d / reach;
        const push = near * near * SEPARATION;
        const areaA = a.size * a.size;
        const areaB = b.size * b.size;
        const shareA = areaB / (areaA + areaB);
        const shareB = areaA / (areaA + areaB);
        a.gustVx -= nx * push * shareA;
        b.gustVx += nx * push * shareB;
        // Mostly a sideways puff. A strong vertical component reads as a flake
        // stopping in mid-air, so the along-axis part of the push is damped.
        a.gustVy -= ny * push * shareA * 0.4;
        b.gustVy += ny * push * shareB * 0.4;
        const graze = (nx * rvy - ny * rvx) * near * GRAZE_SPIN;
        a.gustSpin -= graze * shareA;
        b.gustSpin += graze * shareB;
      }
    }
    for (const flake of flakes) {
      flake.gustVx = Math.max(-GUST_MAX, Math.min(GUST_MAX, flake.gustVx * GUST_DECAY));
      flake.gustVy *= GUST_DECAY;
      flake.gustSpin *= GUST_DECAY;
    }
  }

  /**
   * Runs the fixed-step simulation for one animation frame, dropping any backlog
   * beyond {@link MAX_STEPS} so a stalled tab resumes in step rather than lurching.
   *
   * @param {number} now milliseconds from the monotonic clock
   * @returns {number} how many simulation steps ran
   */
  function simulate(now) {
    const elapsed = Math.min(now - state.last, MAX_STEPS * FRAME_MS);
    state.last = now;
    state.accumulator += elapsed;
    let steps = 0;
    while (state.accumulator >= FRAME_MS && steps < MAX_STEPS) {
      separateFlakes(state.flakes);
      for (const flake of state.flakes) {stepFlake(flake, state.bounds, state.rnd);}
      state.accumulator -= FRAME_MS;
      steps++;
    }
    if (steps === MAX_STEPS) {state.accumulator = 0;}
    return steps;
  }

  /**
   * Paints the scene into the transferred canvas, clearing only the rectangles
   * the previous frame painted. A full-viewport clear costs millions of pixels to
   * wipe for a few hundred pixels of snow; this costs the snow.
   *
   * @returns {void}
   */
  function paintOffscreen() {
    const ctx = state.ctx;
    const buf = state.buffer;
    const rects = state.dirtyRects;
    const n = state.flakes.length;
    if (state.dirtyAll) {
      ctx.clearRect(0, 0, state.canvas.width, state.canvas.height);
      state.dirtyAll = false;
    } else {
      for (let i = 0; i < state.dirty; i++) {
        const at = i * 4;
        ctx.clearRect(rects[at], rects[at + 1], rects[at + 2], rects[at + 3]);
      }
    }
    for (let i = 0; i < n; i++) {
      const at = i * STRIDE;
      const a = buf[at + F_A];
      const b = buf[at + F_B];
      const cell = buf[at + F_CELL] | 0;
      const col = (cell % state.layout.cols) * CELL;
      const row = Math.floor(cell / state.layout.cols) * CELL;
      ctx.setTransform(a, b, buf[at + F_C], buf[at + F_D], buf[at + F_X], buf[at + F_Y]);
      ctx.globalAlpha = buf[at + F_ALPHA];
      ctx.drawImage(state.atlas, col, row, CELL, CELL, -1, -1, 2, 2);
      // |a| + |b| is the rotated sprite's half-extent on each axis, so the rect
      // recorded here is exactly the box the next frame has to erase.
      const half = Math.abs(a) + Math.abs(b);
      const rt = i * 4;
      rects[rt] = buf[at + F_X] - half;
      rects[rt + 1] = buf[at + F_Y] - half;
      rects[rt + 2] = half * 2;
      rects[rt + 3] = half * 2;
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    state.dirty = n;
  }

  /**
   * Whether this beat of the loop is one that gets drawn, as opposed to one that
   * only advances the physics.
   *
   * This is the whole of the frame-rate reduction: the loop keeps running at the
   * display's rate, {@link simulate} keeps stepping the fixed timestep, and only
   * the packing and the drawing are skipped. A flake that is not drawn stays on
   * screen from the previous beat, so the field never flickers — it just updates
   * less often.
   *
   * @param {number} beat beats of the loop since it started, or since the last
   *   rate change, counting from 0
   * @param {number} rate beats to skip between paints, at least 1
   * @returns {boolean} true when this beat should be packed and drawn
   */
  function paintBeat(beat, rate) {
    return beat % Math.max(1, rate | 0) === 0;
  }

  /**
   * Records one beat of the loop and reports the measured rate to the page every
   * {@link STATS_MS} so the page can decide whether to thin the paint or the field.
   *
   * It counts beats, not paints, and that distinction is what keeps the controller
   * from degrading forever. A skipped paint is cheaper, so the loop recovers to the
   * display's rate; if this counted paints instead, the reported rate would sit at
   * 30 by construction, below the page's slow threshold, and the controller would
   * keep cutting a field that had already caught up.
   *
   * @param {number} now milliseconds from the monotonic clock
   * @returns {void}
   */
  function noteFrame(now) {
    state.frames++;
    if (now - state.statsAt < STATS_MS) {return;}
    const elapsed = now - state.statsAt;
    send({type: "stats", fps: elapsed > 0 ? (state.frames * 1000) / elapsed : 0, count: state.count});
    state.frames = 0;
    state.statsAt = now;
  }

  /**
   * One animation frame in offscreen mode: simulate, maybe paint, schedule the next.
   *
   * The simulate always runs; only the draw is subject to {@link paintBeat}, so a
   * reduced paint rate changes how often the field is redrawn, never how fast it
   * falls.
   *
   * @param {number} now milliseconds from the monotonic clock
   * @returns {void}
   */
  function offscreenFrame(now) {
    if (!state.running) {return;}
    simulate(now);
    const draw = paintBeat(state.beat, state.rate);
    state.beat++;
    if (draw) {
      packFrame();
      paintOffscreen();
    }
    noteFrame(now);
    schedule();
  }

  /**
   * Arms the next frame. A dedicated worker has requestAnimationFrame only in
   * Chromium, so the cadence is chosen once and a timer stands in elsewhere.
   *
   * @returns {void}
   */
  function schedule() {
    if (!state.running) {return;}
    if (state.cadence === "raf") {
      self.requestAnimationFrame(offscreenFrame);
    } else if (state.cadence === "timer") {
      state.timer = self.setTimeout(() => offscreenFrame(clock()), FRAME_MS);
    }
  }

  /**
   * Reads the monotonic clock, falling back to the wall clock where a worker
   * build ships no performance object.
   *
   * @returns {number} milliseconds
   */
  function clock() {
    return typeof performance !== "undefined" && performance.now ? performance.now() : Date.now();
  }

  /**
   * Packs the current flake field into a fresh transferable buffer.
   *
   * @returns {void}
   */
  function packFrame() {
    const n = state.flakes.length;
    if (!state.buffer || state.buffer.length < n * STRIDE) {
      state.buffer = new Float32Array(Math.max(1, n) * STRIDE);
    }
    for (let i = 0; i < n; i++) {writeFlake(state.flakes[i], state.buffer, i, state.dpr);}
    state.frameLength = n;
  }

  /**
   * Starts the simulation once the page has answered the atlas handshake.
   *
   * @returns {void}
   */
  function start() {
    if (state.running || !state.prepared) {return;}
    state.running = true;
    state.last = clock();
    state.statsAt = state.last;
    state.frames = 0;
    state.dirtyAll = true;
    if (state.offscreen) {schedule();}
  }

  /**
   * Stops the simulation, leaving the flake field in place so a hidden tab
   * resumes where it left off instead of rebuilding.
   *
   * @returns {void}
   */
  function stop() {
    state.running = false;
    if (state.timer !== null && state.timer !== undefined) {self.clearTimeout(state.timer);}
    state.timer = null;
    state.accumulator = 0;
  }

  /**
   * Begins the handshake with the page: agree on the mode, publish the atlas
   * table, and wait for the rasterized atlas before any flake is drawn.
   *
   * @param {Object} msg the init message from the page
   * @returns {void}
   */
  function init(msg) {
    state.bounds.width = Math.max(1, Number(msg.width) || 1);
    state.bounds.height = Math.max(1, Number(msg.height) || 1);
    state.dpr = Math.max(1, Math.min(3, Number(msg.dpr) || 1));
    // A machine the page has already judged weak starts at the reduced paint rate
    // rather than discovering it after four seconds of visible stutter.
    setRate(Number(msg.rate));
    state.base = Math.max(1, Number(msg.count) || flakeCountFor(state.bounds.width, state.bounds.height));
    state.rnd = prng(Number(msg.seed) || (Date.now() & 0x7fffffff));
    state.offscreen = !!msg.canvas && offscreenAvailable();
    if (state.offscreen) {
      state.canvas = msg.canvas;
      state.ctx = state.canvas.getContext("2d");
      state.cadence = typeof self.requestAnimationFrame === "function" ? "raf" : "timer";
      sizeCanvas();
    }
    state.layout = atlasLayout(state.base * FRAMES);
    const slots = buildSlots(state.base, state.rnd);
    state.shapes = slots.shapes;
    state.wire = slots.wire;
    state.palette = buildPalette(state.base, msg.baseColor, state.rnd, state.shapes.length);
    state.dirtyRects = new Float32Array(state.base * 4);
    state.dirty = 0;
    state.dirtyAll = true;
    setCount(state.base);
    state.rasterized = state.offscreen && rasterAtlas();
    send({
      type: "ready",
      offscreen: state.offscreen,
      raster: state.rasterized,
      count: state.base,
      cell: CELL,
      stride: STRIDE,
      frames: FRAMES,
      layout: state.layout,
      cells: state.palette,
      shapes: state.shapes
    });
    // In offscreen mode the worker paints, so it rasterizes its own atlas and gets
    // straight on with the animation; the page is not asked to decode anything. In
    // the fallback the page rasterizes and answers with the bitmap, and the worker
    // waits for it.
    if (state.rasterized) {
      state.prepared = true;
      start();
    }
  }

  /**
   * Handles one message from the page.
   *
   * @param {MessageEvent} event the message
   * @returns {void}
   */
  function onMessage(event) {
    const msg = event.data || {};
    switch (msg.type) {
      case "init":
        init(msg);
        break;
      case "atlas":
        if (msg.bitmap) {state.atlas = msg.bitmap;}
        state.prepared = true;
        start();
        break;
      case "tick": {
        if (!state.prepared || !state.running) {break;}
        const now = Number(msg.t) || clock();
        simulate(now);
        const draw = paintBeat(state.beat, state.rate);
        state.beat++;
        // Skipping the pack as well as the send is the point: on a throttled beat
        // nothing is copied, nothing is transferred, and the page paints nothing.
        if (draw) {
          packFrame();
          const out = state.buffer.slice(0, state.frameLength * STRIDE);
          send({type: "frame", buf: out, count: state.frameLength}, [out.buffer]);
        }
        noteFrame(now);
        break;
      }
      case "view": {
        state.bounds.width = Math.max(1, Number(msg.width) || state.bounds.width);
        state.bounds.height = Math.max(1, Number(msg.height) || state.bounds.height);
        state.dpr = Math.max(1, Math.min(3, Number(msg.dpr) || state.dpr));
        sizeCanvas();
        retargetTravel();
        state.dirtyAll = true;
        // The page faded the canvas out while its backing store lagged the CSS
        // box; this is the signal that the two agree again.
        send({type: "viewed", width: state.bounds.width, height: state.bounds.height});
        break;
      }
      case "quality":
        setCount(Number(msg.count) || state.count);
        state.dirtyAll = true;
        break;
      case "rate":
        setRate(Number(msg.rate));
        break;
      case "visible":
        if (msg.visible) {start();} else {stop();}
        break;
      case "stop":
        stop();
        break;
      default:
        break;
    }
  }

  self.onmessage = onMessage;

  // The JS harness (tools/test/js/pure/snowman.test.js) loads this file in a
  // worker-like context and drives these directly, so the physics is pinned
  // without a browser.
  self.SNOWMAN = Object.freeze({
    FRAME_MS, STRIDE, CELL, MIN_FLAKES, MAX_FLAKES, DEATH_SPIN_STEPS, DEATH_SPIN_GROWTH,
    REMOVAL_SIZE,
    DEATH_SPIN_MAX, DEATH_SPIN_BIAS, DEATH_SPIN_REVERSE, DEATH_WOBBLE_MIN, DEATH_WOBBLE_MAX,
    DEATH_DRIFT_DECAY, SPIN_LATERAL, DEATH_FALL_ACCEL, SOFT_REACH, SEPARATION, GUST_DECAY,
    GUST_MAX, GRAZE_SPIN, WARP_CHANCE, WARP_MAX, FRAMES, FRAME_BEATS, TUMBLE_BEATS,
    WIRE_STROKE_NEAR, WIRE_STROKE_FAR, HABITS, ICOSA, WIRE,
    SHAPE_VIEW, RATE_MAX,
    flakeCountFor, atlasLayout, prng, parseHex, varyColor, buildPalette, coord,
    spike, plate, hexPath, ngonPath, dot, bar, band, star, stellate, rotAxis,
    armDendrite, armDodeca, buildShape, buildShapes, buildSlots, shapePaths,
    pickHabit, seedDeathSpin, deathSpinAt, separateFlakes,
    createFlake, respawn, spinSpeed, stepFlake, writeFlake, paintBeat
  });
})();
