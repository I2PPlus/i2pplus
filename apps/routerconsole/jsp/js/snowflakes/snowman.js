/**
 * @module snowman
 * @description Dedicated worker that drives the router console's snow. It owns
 * every flake's state, integrates the drift on a fixed timestep, and paints the
 * scene itself when the page hands it an OffscreenCanvas; where the browser will
 * not, it posts one packed Float32Array of draw transforms per frame and lets the
 * page paint them. Either way the page runs no animation math.
 *
 * The drift, deterioration and death-spin constants are inherited from Snowflakes
 * © 2024 Denis Seleznev (MIT, https://github.com/hcodes/snowflakes/), and are written
 * per 60Hz frame exactly as his DOM animation had them, applied by a fixed 16.667ms
 * step. A 30Hz or 144Hz display therefore gets the same fall speed and the same
 * sparkle noise as a 60Hz one, which the per-frame version could not promise.
 *
 * It also draws the flakes. Each one is a generated SVG crystal: a single arm of
 * filled, tapered spikes, replicated six times about the centre, so the six-fold
 * symmetry is a property of the construction rather than of the numbers chosen.
 * One shape per flake slot, about 1.7 KB of markup and 0.05 ms each, sent to the
 * page with the ready message for it to rasterize into the sprite atlas. That
 * replaces fetching the six hand-drawn files, which are kept only as a fallback.
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
  /**
   * Most flakes the tuning panel can ask for, above the automatic {@link MAX_FLAKES}.
   *
   * The field's own ceiling comes from the viewport area; this is the reviewer's
   * override, and it is a ceiling on flakes rather than on crystals — the atlas holds
   * {@link MAX_FLAKES} slots and a hundred flakes draw from those slots, several to a
   * crystal, which is what a deliberately over-snowed page looks like.
   */
  const TUNING_MAX_FLAKES = 100;
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
   * Chance a stem habit's crystal gets the spiral that joins its arms to their
   * neighbours.
   *
   * Mostly, not always. The join is what makes a spoke-and-plate crystal read as one
   * object rather than as six separate sticks, and a field where every crystal is
   * joined loses the difference; a field where none of them are is the diagram look
   * the stem habits are capped for. The decision is made from the slot's own seed, so
   * all sixteen frames of a crystal agree and a flake never flickers between joined
   * and broken.
   */
  const SPIRAL_CHANCE = 0.7;
  /**
   * Chance a replacement flake is given a brand-new crystal instead of the one its
   * slot held.
   *
   * One crystal set is twenty-odd shapes, and a slot is reused every few minutes, so
   * without a refresh the viewer watches the same handful of crystals for as long as
   * the page is open. Refreshing a few of the slots at a time turns the field over
   * continuously — about six new crystals in every twenty replacements, so a whole set
   * has turned over in a few minutes of snow — without ever changing the shape of a
   * flake that is on screen.
   *
   * The refresh happens on the step a flake is recycled, when it is above the top
   * edge and has not been drawn yet, and it repaints only that slot's cells in the
   * atlas. A rate high enough to look like a reshuffle, or a refresh applied to a
   * slot a live flake is holding, is what would make the snow visibly re-draw itself.
   */
  const CRYSTAL_REFRESH_CHANCE = 0.3;
  /**
   * Hue spread, in degrees, that a variation setting of 100% puts across the field.
   *
   * Half of that is what a single crystal may sit off the theme colour by, on either
   * side. Much past this and a field of snow stops reading as one colour at all, which
   * is the effect the control is meant to tune rather than overshoot.
   */
  const HUE_SPREAD_MAX = 120;
  /**
   * Hue degrees a colour cycle moves per second.
   *
   * A full turn of the wheel in a minute: slow enough that no single flake is visibly
   * shifting while it falls — a flake is on screen for a few seconds, which is a handful
   * of degrees — and fast enough that a field watched for a minute is unmistakably a
   * different colour. The panel's thumbnails cycle at the same rate.
   */
  const CYCLE_DEGREES_PER_SEC = 6;
  /**
   * How many fixed steps pass between the colour cycle re-sending the atlas to a page
   * that rasterizes it — one second of simulated time.
   *
   * The page rebuilds that atlas by decoding every shape, which is work worth doing once
   * a second for a colour drift and not worth doing thirty times a second.
   */
  const CYCLE_PAGE_STEPS = 30;
  /**
   * Field defaults the panel resets to.
   *
   * Kept as named constants because the reset button has to put back exactly what the
   * worker started with, and a second copy of these numbers in the panel drifts.
   */
  const DEFAULT_VARIATION = 0.15;
  /** Per-flake gravity spread the field starts with, as a fraction of each flake's own. */
  const DEFAULT_GRAVITY_VAR = 0;
  /**
   * Per-flake spin spread the field starts with, as a fraction of each flake's own.
   *
   * 1, not 0: every flake is dealt its own rotation rate, so the field has always turned
   * at a dozen rates, and the slider is set to that spread rather than to a spread on top
   * of it. At 0 the dealt rates are switched off and the whole field turns at the floor.
   */
  const DEFAULT_SPIN_VAR = 1;
  /**
   * Widest per-crystal spin deviation range the panel may set, as a multiple of the spread
   * the field is dealt. Above 1 the flakes turn at more than the rates they were drawn at;
   * the spin limits in scaleSpin still bound what any one flake actually turns at.
   */
  const SPIN_VAR_MAX = 2;
  /**
   * The size, in CSS pixels, a flake is dealt at before its own spread and the panel's
   * multiplier are applied.
   *
   * The field's natural spread is a fraction of this rather than a range of sizes, so the
   * size slider moves the whole field up and down without changing how varied it is, and
   * the spread slider widens it without moving the average.
   */
  const BASE_SIZE = 12.2;
  /**
   * Smallest size, in CSS pixels, a flake may be dealt at relative to {@link BASE_SIZE},
   * however wide the spread is set. Below this the crystal's own detail — the holes in a
   * hollow plate, the beads on a beaded arm — closes up and the habit stops reading.
   */
  const MIN_SIZE = 11;
  /**
   * Per-flake size spread the field starts with, as a fraction of {@link BASE_SIZE}.
   *
   * This is the spread the field was dealt with before the panel could set one: 12.2 ± 27%
   * is 8.9 to 15.5 pixels, which the {@link MIN_SIZE} floor lifts to the 11 to 16 range the
   * old inline formula produced. The slider's default is this so the field looks the same
   * before anyone touches it as it did before the slider existed.
   */
  const DEFAULT_SIZE_VAR = 0.27;
  /** Field size multiplier the field starts at and the panel resets to. */
  const DEFAULT_SIZE = 1;
  /**
   * Smallest and largest size multipliers the panel may set. The floor keeps a flake
   * large enough to rasterize and read as a crystal; the ceiling is where one flake starts
   * covering the console's text.
   */
  const SIZE_MIN = 0.4;
  const SIZE_MAX = 2.5;
  /**
   * Size multiplier the figure habit is dealt at, on top of the field's own size.
   *
   * A figure is a recognisable object rather than a texture: at the size a crystal is
   * painted, the hat brim and the twig arms land under a pixel each and the snowman reads
   * as a smudge. The in-cell scale column of {@link HABITS} cannot fix this — it shrinks
   * the drawing inside the sprite, so raising it above 1 would clip the hat off the top of
   * the atlas cell. Growing the flake is what makes the figure legible without moving any
   * of the crystals beside it.
   */
  const FIGURE_SIZE = 1.25;
  /**
   * Lowest and highest fall-rate and spin multipliers the panel may set. The floor on
   * gravity is not zero because a field that does not fall is not snow, and the ceiling
   * is where the fixed step stops being able to follow a flake across the viewport.
   */
  const MOTION_MIN = 0.2;
  const MOTION_MAX = 3;
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
   * Sixteen rather than eight because the flat crystal's warp cycle is the binding
   * constraint: at eight frames the twist moves 20 degrees at a time and the
   * reforming reads as a flip book rather than as a slow untwist.
   */
  const FRAMES = 16;
  /** Simulation steps a flat flake's frame is held for; the reforming cycle's pace. */
  const FRAME_BEATS = 12;
  /**
   * Stroke widths of a wireframe, from the edge nearest the viewer to the farthest,
   * as multiples of {@link WIRE_STROKE_NEAR}.
   *
   * A near/far cut — draw the front edges, drop the back ones — makes an edge pop
   * into existence the moment it crosses the view plane, and a line appearing in the
   * middle of a slowly turning crystal is what reads as jank. Width follows depth
   * along this ramp instead, so an edge grows out of invisibility as it turns toward
   * the viewer. The far end of the ramp is deliberately under a pixel at the size a
   * flake is shown: that is what keeps the middle of the sprite clear of the back
   * half of the solid.
   */
  const WIRE_DEPTH = [0.1, 0.25, 0.42, 0.62, 0.82, 1];
  /** Stroke width of a wireframe edge nearest the viewer, in shape units. */
  const WIRE_STROKE_NEAR = 4;
  /**
   * Fastest a wireframe flake turns, in radians per step.
   *
   * A solid that turns quickly reads as a drill rather than as a crystal, and a
   * twelve-pointed silhouette aliases at half the rate a six-fold one does, so the
   * wireframe habit is capped well below the flat crystals' range: this is about
   * 45 degrees a second, a full turn of the z axis in eight seconds.
   */
  const WIRE_SPIN_MAX = 0.013;
  /**
   * Slowest a wireframe flake turns, in radians per step: about 21 degrees a second,
   * so even a flake that drew a near-zero spin still completes a full turn well inside
   * its travel.
   */
  const WIRE_SPIN_MIN = 0.006;
  /**
   * Slowest and fastest a wireframe flake tumbles about each of the two screen axes,
   * in radians per step: 17 to 34 degrees a second.
   *
   * A floor on the magnitude, not a range around zero. Drawn from a range centred on
   * zero, a third of the field drew a near-zero rate on one axis or the other and fell
   * turning on two axes while looking flat, which is what a solid built in three
   * dimensions is not supposed to do. Each axis draws its own rate and its own sign,
   * so the three axes turn at different speeds and no two wireframes tumble in step.
   */
  const WIRE_TUMBLE_MIN = 0.005;
  const WIRE_TUMBLE_MAX = 0.01;
  /** Fastest a flat flake turns while it is alive, in radians per step. */
  const MIN_SPIN = 0.01;
  /**
   * Fall speed that counts as slow, in pixels a step. Everything under this is one of
   * the smallest flakes in the field, and a small flake drifting down almost square
   * to the viewer reads as a speck rather than as a crystal.
   */
  const SLOW_FALL_SPEED = 0.78;
  /**
   * How much spin a slow flake picks up over its fall, as a multiple of the rate it
   * started at, reached at the end of its travel.
   *
   * Two reasons, and they are the same reason: air catches a face and a small flake
   * really does spin up as it falls, and a field whose slow third never turns is a
   * field with no spin variation in it. The fast flakes get their spread of rates from
   * their size; this is what gives the slow ones theirs.
   */
  const SLOW_SPIN_RAMP = 1.6;
  /**
   * Fastest a dying wireframe spins, in radians per step, against the
   * {@link DEATH_SPIN_MAX} a flat crystal reaches.
   *
   * The death spiral is the same event for both, but a solid thrown about at a flat
   * crystal's rate stops reading as one at all.
   */
  const WIRE_DEATH_SPIN_MAX = 0.11;
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
  /**
   * Formats an rgb triple as the hex form the palette builder takes.
   *
   * @param {number[]} rgb the colour as [r, g, b]
   * @returns {string} the colour as #rrggbb, each channel clamped to 8 bits
   */
  function rgbText(rgb) {
    const hex = v => Math.max(0, Math.min(255, v | 0)).toString(16).padStart(2, "0");
    return "#" + hex(rgb[0]) + hex(rgb[1]) + hex(rgb[2]);
  }

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
    return colorText(base, varyDelta(base, maxVar, rnd));
  }

  /**
   * The per-slot colour offset that makes a field of flakes one colour without making
   * them all the same colour.
   *
   * Kept separate from the formatting so the offset can be re-applied to a different
   * base — which is what the panel's hue control does: it moves the base and every slot
   * keeps the offset it was dealt, so turning the hue shifts the field's palette instead
   * of reshuffling it.
   *
   * @param {number[]} base theme colour as [r, g, b]
   * @param {number} maxVar largest per-channel offset, in 8-bit steps
   * @param {function(): number} rnd source of randomness
   * @returns {number[]} the per-channel offset, [dr, dg, db]
   */
  function varyDelta(base, maxVar, rnd) {
    if (rnd() < 0.5) {
      const tint = FESTIVE_TINTS[Math.floor(rnd() * FESTIVE_TINTS.length)];
      const mix = 0.075;
      return [base[0] * (1 - mix) + tint[0] * mix + rnd() * maxVar - maxVar / 2 - base[0],
              base[1] * (1 - mix) + tint[1] * mix + rnd() * maxVar - maxVar / 2 - base[1],
              base[2] * (1 - mix) + tint[2] * mix + rnd() * maxVar - maxVar / 2 - base[2]];
    }
    return [rnd() * maxVar * 3 - maxVar, rnd() * maxVar * 3 - maxVar, rnd() * maxVar * 3 - maxVar];
  }

  /**
   * Formats a base colour with a per-slot offset as a CSS colour.
   *
   * @param {number[]} base the colour as [r, g, b]
   * @param {number[]} delta the per-channel offset, [dr, dg, db]
   * @returns {string} the colour as rgb(r,g,b), each channel clamped to 8 bits
   */
  function colorText(base, delta) {
    const clamp = v => Math.max(0, Math.min(255, v | 0));
    return "rgb(" + clamp(base[0] + delta[0]) + "," + clamp(base[1] + delta[1]) + "," +
                    clamp(base[2] + delta[2]) + ")";
  }

  /**
   * Rotates a colour about its hue, leaving its lightness and saturation alone.
   *
   * @param {number[]} rgb the colour as [r, g, b] in 8-bit steps
   * @param {number} degrees the rotation, clockwise on the colour wheel
   * @returns {number[]} the rotated colour as [r, g, b]
   */
  /**
   * The hue of a colour, in degrees on the colour wheel.
   *
   * A grey has no hue of its own and reports 0, which is the honest answer for a colour
   * the wheel cannot place: the caller that wants to match or oppose a colour has to
   * look at its saturation for itself.
   *
   * @param {number[]} rgb the colour as [r, g, b] in 8-bit steps
   * @returns {number} the hue in [0, 360)
   */
  function hueOf(rgb) {
    const r = rgb[0] / 255;
    const g = rgb[1] / 255;
    const b = rgb[2] / 255;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const span = max - min;
    if (span === 0) {return 0;}
    let hue;
    if (max === r) {hue = (g - b) / span + (g < b ? 6 : 0);}
    else if (max === g) {hue = (b - r) / span + 2;}
    else {hue = (r - g) / span + 4;}
    return hue * 60;
  }

  function rotateHue(rgb, degrees) {
    const r = rgb[0] / 255;
    const g = rgb[1] / 255;
    const b = rgb[2] / 255;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const light = (max + min) / 2;
    const span = max - min;
    if (span === 0) {return rgb.slice();}
    const saturation = span / (light > 0.5 ? 2 - max - min : max + min);
    let hue = (hueOf(rgb) + degrees) / 360 % 1;
    if (hue < 0) {hue += 1;}
    /**
     * One channel of an HSL colour, as the hue sector it falls in.
     * @param {number} q the HSL q term
     * @param {number} p the HSL p term
     * @param {number} t the channel's position on the wheel
     * @returns {number} the channel in [0, 1]
     */
    function channel(q, p, t) {
      if (t < 0) {t += 1;}
      if (t > 1) {t -= 1;}
      if (t < 1 / 6) {return q + (p - q) * 6 * t;}
      if (t < 1 / 2) {return q;}
      if (t < 2 / 3) {return q + (p - q) * (2 / 3 - t) * 6;}
      return p;
    }
    const q = light < 0.5 ? light * (1 + saturation) : light + saturation - light * saturation;
    const p = 2 * light - q;
    const clamp = v => Math.max(0, Math.min(255, Math.round(v * 255)));
    return [clamp(channel(q, p, hue + 1 / 3)), clamp(channel(q, p, hue)), clamp(channel(q, p, hue - 1 / 3))];
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
      const delta = varyDelta(rgb, 10, rnd);
      // The slot's own place in the palette's spread, fixed when it is dealt: the hue
      // control moves the whole field together and the variation control widens the
      // spread around it, and neither would do either if this number were redrawn each
      // time.
      cells.push({type, delta, spread: rnd() * 2 - 1, color: colorText(rgb, delta)});
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
   * @param {boolean} [reverse] walk the corners the other way round, which is what
   *   makes the polygon a hole rather than a fill when it sits inside another path
   * @returns {string} the closed path data, with no element around it
   */
  function ngonPath(cx, cy, radius, sides, rotation, reverse) {
    const twist = warpAt(cx, cy);
    if (twist) {
      const at = warpPoint(cx, cy, twist);
      cx = at[0];
      cy = at[1];
      rotation += twist;
    }
    let d = "";
    for (let k = 0; k < sides; k++) {
      const a = rotation + (reverse ? -1 : 1) * k * 2 * Math.PI / sides;
      d += (k ? "L" : "M") + coord(cx + Math.sin(a) * radius) + " " + coord(cy - Math.cos(a) * radius);
    }
    return d + "Z";
  }

  /**
   * Builds a hollow polygon: a plate with a hole in it, as one path.
   *
   * A hole is the one thing a filled silhouette cannot otherwise do, and it is what
   * the hollow-plate class is made of. The inner edge is wound the other way so the
   * non-zero fill rule both SVG and the worker's own rasterizer use leaves it empty.
   * At the 11-16 pixels a flake is shown at, a plate with a hole still reads as a
   * plate where a solid one of the same size reads as a dot.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} outer distance from centre to a vertex of the outer edge
   * @param {number} inner distance from centre to a vertex of the hole
   * @param {number} sides number of corners, at least 3
   * @param {number} rotation orientation in radians
   * @returns {string} one filled path element with a hole in it
   */
  function hollowPlate(cx, cy, outer, inner, sides, rotation) {
    return '<path d="' + ngonPath(cx, cy, outer, sides, rotation) +
           ngonPath(cx, cy, inner, sides, rotation, true) + '"/>';
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
      // Decided once per pair, not per side: a second-order branch that is longer on
      // one side of the arm than the other is the stray tail that showed up on a
      // single arm of some flakes, and the plate on a branch tip is the blob version
      // of the same thing.
      const branched = rnd() < 0.6;
      const branchLen = len * (0.34 + rnd() * 0.26);
      const branchLean = (0.5 + rnd() * 0.45) * Math.PI / 3 * 0.9;
      const capped = rnd() < 0.35;
      const cap = Math.max(1.5, len * (0.11 + rnd() * 0.08));
      for (const side of [-1, 1]) {
        const angle = side * spread;
        parts.push(spike(0, -at, angle, len, half, 0));
        const tipX = Math.sin(angle) * len;
        const tipY = -at - Math.cos(angle) * len;
        // second-order branching, which is what makes a crystal dendritic
        if (branched) {
          parts.push(spike(tipX, tipY, angle + side * branchLean, branchLen, half * 0.55, 0));
        }
        // a plate capping a branch tip
        if (capped) {
          parts.push(stellate(tipX, tipY, cap, rnd(), rnd));
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
   * The notch is cut deep on purpose. A shallow one — a notch at half the point
   * radius — is a hexagon with its corners chamfered, and at the 11-16 pixels a flake
   * is shown at, six overlapping copies of that merge into one bright blob in the
   * middle of the sprite. At this depth the points stay separate spikes with clear
   * sky between them, which is the crystalline structure the plate is here to add.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius distance from centre to a point
   * @param {number} rotation orientation in radians
   * @param {function(): number} rnd source of randomness for the notch depth
   * @returns {string} one filled path element
   */
  function stellate(cx, cy, radius, rotation, rnd) {
    return star(radius, radius * (0.3 + rnd() * 0.16), 6, rotation, cx, cy);
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
  /**
   * A spiral running from the point of one arm inward to the trunk of the next, so
   * every arm of the crystal is joined to its neighbour.
   *
   * It is built out of straight facets rather than one smooth arc. A real spiral arm is
   * a run of straight growth segments joined at slight angles, and a smooth ribbon was
   * what made the needle habit read as a flower: on a crystal made of tapered spikes
   * and hexagonal faces, the curve is the only round thing there, so it is the first
   * thing the eye reads.
   *
   * The three facets are placed along a quadratic that starts at the arm's point and
   * ends on the neighbouring arm's own axis, so the join is exact rather than a
   * near-miss: the whole point of the element is that no arm stands on its own, and a
   * spiral that stops short reads as a broken flake. The control point sits outside the
   * chord, which is what bows it the way a spiral arm bows.
   *
   * @param {Function} rnd the flake's own generator, for the sweep's depth and width
   * @param {number} trunk radius of the arm's point, in shape units
   * @param {number} base half width of that arm's trunk, in shape units
   * @param {number} [sweep] angle to the neighbour the spiral lands on, in radians:
   *   60 degrees for a six-fold crystal, 30 for the twelve-branched twin
   * @param {number} [depth] how far in the spiral falls, as a fraction of the arm's
   *   length. The default is a shallow sweep, which is what an arm with barbs along it
   *   wants; a habit whose arm is bare — a capped column, a beaded spine — needs to
   *   fall much further or the six copies close into a rim, and a rim on six spokes is
   *   the ship's wheel again.
   * @returns {string[]} the spiral's filled elements, outermost first
   */
  function spiralVane(rnd, trunk, base, sweep, depth) {
    const arc = sweep === undefined ? Math.PI / 3 : sweep;
    // Thinner than the arm it joins, so the trunk still reads as an arm and the spiral
    // reads as a branch of it rather than as the whole crystal.
    const wide = base * (0.42 + rnd() * 0.3);
    const land = trunk * (depth === undefined ? 0.66 + rnd() * 0.16 : depth + rnd() * 0.14);
    const start = trunk - wide;
    const half = arc / 2;
    const bulge = (start + land) / 2 * (1.12 + rnd() * 0.1);
    const p0 = [0, -start];
    const p1 = [Math.sin(half) * bulge, -Math.cos(half) * bulge];
    const p2 = [Math.sin(arc) * land, -Math.cos(arc) * land];
    const parts = [];
    let prev = p0;
    for (let i = 1; i <= 3; i++) {
      const t = i / 3;
      const mt = 1 - t;
      const x = mt * mt * p0[0] + 2 * mt * t * p1[0] + t * t * p2[0];
      const y = mt * mt * p0[1] + 2 * mt * t * p1[1] + t * t * p2[1];
      parts.push(spike(prev[0], prev[1], Math.atan2(x - prev[0], -(y - prev[1])),
                       Math.hypot(x - prev[0], y - prev[1]),
                       wide * (1 - 0.15 * t), wide * (1 - 0.25 * t)));
      prev = [x, y];
    }
    return parts;
  }

  function armNeedle(rnd, radius) {
    // Everything the habit draws has to fit inside radius: the whole crystal is
    // already scaled down, and a cap or a spur hanging past it puts the flake back
    // over the seven tenths a needle is allowed to cover.
    const trunk = radius * (0.76 + rnd() * 0.08);
    const base = radius * (0.055 + rnd() * 0.025);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.3)];
    // Barbs, not a bare stick. A real needle is a columnar crystal and the
    // reference sheets draw them with a few short side spurs; without them the
    // habit reads at 12 pixels as a spoke from a wheel rather than as a flake.
    // Kept short and thin, and one set of numbers per pair: long barbs on six arms
    // reach into their neighbours' space and merge into one flower, which is what
    // this habit used to look like, and a spur longer on one side than the other is
    // the stray tail that showed up on a single arm.
    const barbs = 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < barbs; i++) {
      const at = trunk * ((i + 0.6) / (barbs + 0.9));
      const len = (trunk - at) * (0.26 + rnd() * 0.18);
      const lean = 0.75 + rnd() * 0.3;
      const half = base * (0.7 + rnd() * 0.4);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * lean, len, half, 0));
      }
    }
    // The spiral that joins the arms: six copies of it run from the point of each
    // arm down into the trunk of the next, so the habit reads as one curved crystal
    // rather than as six spokes, and no arm is left standing on its own.
    if (rnd() < SPIRAL_CHANCE) {
      parts.push(...spiralVane(rnd, trunk, base));
    }
    // A face on the point. The vane leaves from here, and without something solid at
    // that join the six hooks read as six separate strokes rather than as one crystal.
    parts.push(stellate(0, -trunk, base * (1.5 + rnd() * 0.6), rnd() * Math.PI / 3, rnd));
    if (rnd() < 0.55) {
      parts.push(stellate(0, -trunk * (0.45 + rnd() * 0.25), base * (1.3 + rnd() * 0.5), rnd() * Math.PI / 3, rnd));
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
    const trunk = radius * (0.82 + rnd() * 0.14);
    const base = radius * (0.045 + rnd() * 0.016);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.25)];
    // The chevrons are the sectored plate's ridges, and they are also the way this
    // habit over-fills itself: stacked close, wide and long, six arms of them merge
    // into one solid stellated disc with no sky anywhere in it. Fewer, shorter and
    // further apart keeps the stepped outline the habit is named for, and leaves the
    // trunk readable between each pair, which is what makes it a crystal rather than
    // a filled star.
    const chevrons = 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < chevrons; i++) {
      const f = (i + 0.55) / (chevrons + 0.5);
      const at = trunk * f;
      const len = (trunk - at) * (0.42 + rnd() * 0.26);
      const lean = 0.75 + rnd() * 0.25;
      const half = base * (0.8 + rnd() * 0.45) * (1 - f * 0.3);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * lean, len, half, 0));
      }
    }
    // A face on the point, so the arm ends in a crystal rather than a plain taper.
    if (rnd() < 0.7) {
      parts.push(stellate(0, -trunk, base * (1.4 + rnd() * 0.8), rnd() * Math.PI / 3, rnd));
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
    // The fall is deep here: a capped column's arm is bare apart from its plates, so a
    // shallow sweep would close six of them into a rim around the flake.
    if (rnd() < SPIRAL_CHANCE) {
      parts.push(...spiralVane(rnd, trunk, base, Math.PI / 3, 0.42));
    }
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
    // The join is built off a wider base than the spine: at this habit's spine width
    // the spiral's facets would be hairlines, and the one element whose job is to
    // survive the downsample is the one that joins the arms.
    if (rnd() < SPIRAL_CHANCE) {
      parts.push(...spiralVane(rnd, trunk, base * 1.6, Math.PI / 3, 0.42));
    }
    return parts;
  }

  /**
   * Builds one arm of a ring flake: a barbed spoke, a ring the arm grows itself, and
   * a face on the point.
   *
   * The ring is what makes this habit a ring flake rather than a dendrite, and it is
   * also what made it read as a ship's wheel: a band drawn straight across six
   * straight spokes is a rim on a wheel, and the long spikes this habit used to throw
   * out of the rim's corners were the handles. So the ring is not drawn across the
   * arms — it is the arms' own sidebranches, set at 60 degrees to the trunk and long
   * enough to land on the hexagon corner 30 degrees out, which is exactly where the
   * neighbouring arm's matching branch lands. Six arms, twelve bars, one hexagonal
   * lattice, with a gap wherever the pair fell short.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armRinged(rnd, radius) {
    const trunk = radius * (0.84 + rnd() * 0.12);
    const base = radius * (0.04 + rnd() * 0.014);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.3)];
    // The ring's radius, and the branch that reaches its corner: starting a branch
    // 0.577R out along the trunk and running it 60 degrees off that at the same
    // length puts its point on the corner at radius R, 30 degrees round. Jittering
    // both leaves some corners closed and some open, which is what a real ring flake
    // looks like and what keeps the lattice from reading as a drawn hexagon.
    const ring = trunk * (0.5 + rnd() * 0.16);
    const reach = ring * (0.52 + rnd() * 0.12);
    const lean = Math.PI / 3 * (0.92 + rnd() * 0.16);
    const half = base * (0.85 + rnd() * 0.45);
    for (const side of [-1, 1]) {
      parts.push(spike(0, -reach * Math.cos(lean), side * lean, reach, half, 0));
    }
    // One plate on the corner, drawn by the arm on the clockwise side of it, so the
    // six-fold replication puts exactly one on each corner and it sits where the two
    // branches meet.
    const corner = Math.PI / 6;
    parts.push(stellate(Math.sin(corner) * ring, -Math.cos(corner) * ring,
                        base * (1.6 + rnd() * 0.9), rnd() * Math.PI / 3, rnd));
    // Barbs beyond the ring, on the span of spoke that sticks out past it, which is
    // the part of the arm a reader sees. Mirrored pair, one set of numbers both sides.
    const barbs = 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < barbs; i++) {
      const f = (i + 0.55) / (barbs + 0.45);
      const pos = ring + (trunk - ring) * f;
      const len = (trunk - pos) * (0.4 + rnd() * 0.3);
      const angle = 0.6 + rnd() * 0.3;
      const wide = base * (0.75 + rnd() * 0.4);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -pos, side * angle, len, wide, 0));
      }
    }
    if (rnd() < 0.75) {
      parts.push(stellate(0, -trunk, base * (2 + rnd() * 1), rnd() * Math.PI / 3, rnd));
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
   * Builds one arm of a fernlike stellar dendrite: a thin trunk carrying many closely
   * spaced sidebranches, each of which throws a spur of its own.
   *
   * The plain dendrite above draws three to five branches and stops; this is the
   * fernlike class, whose crystals are the largest and finest there are, and the
   * difference between the two is the thing that makes a field of dendrites read as
   * one silhouette. The rows are packed close and kept thin, because at display size
   * a fern reads as a feathered edge rather than as separate branches — which is the
   * effect being aimed at — and a thick one just fills in.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armFern(rnd, radius) {
    const trunk = radius * (0.86 + rnd() * 0.12);
    const base = radius * (0.026 + rnd() * 0.01);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.15)];
    const rows = 4 + Math.floor(rnd() * 3);
    const lean = 0.85 + rnd() * 0.2;
    const spur = 0.6 + rnd() * 0.3;
    for (let i = 0; i < rows; i++) {
      const f = (i + 0.5) / (rows + 0.3);
      const at = trunk * f;
      const len = (trunk - at) * (0.46 + rnd() * 0.3);
      const half = base * (1 - f * 0.5) * (0.8 + rnd() * 0.3);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * lean, len, half, 0));
        // the fern's own fern: a second-order spur near the tip of each branch,
        // mirrored off the same numbers so both sides feather the same way
        const tx = Math.sin(side * lean) * len;
        const ty = -at - Math.cos(side * lean) * len;
        parts.push(spike(tx, ty, side * (lean + spur), len * (0.3 + rnd() * 0.12), half * 0.5, 0));
      }
    }
    return parts;
  }

  /**
   * Builds one arm of a hollow-plate crystal: a thin spoke with hexagonal rings on it
   * instead of solid plates.
   *
   * This is the class the hand-drawn fallback shapes are mostly made of — hexagon
   * outlines, nested, on the corners of a star — and it is the one the generator had
   * no version of. The rings are drawn as one path with a reversed inner edge, so the
   * hole survives both the SVG fill and the worker's own rasterizer. It only reads on
   * the larger flakes, where the hole is more than a pixel across, which is why the
   * habit is weighted below the solid ones rather than with them.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  function armHexPlate(rnd, radius) {
    const trunk = radius * (0.78 + rnd() * 0.1);
    const base = radius * (0.05 + rnd() * 0.018);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.3)];
    const plate = base * (3 + rnd() * 1.4);
    parts.push(hollowPlate(0, -trunk, plate, plate * (0.42 + rnd() * 0.2), 6, rnd() * Math.PI / 3));
    if (rnd() < 0.6) {
      const at = trunk * (0.45 + rnd() * 0.2);
      const small = plate * (0.55 + rnd() * 0.25);
      parts.push(hollowPlate(0, -at, small, small * (0.42 + rnd() * 0.2), 6, rnd() * Math.PI / 3));
    }
    // Short barbs on the spoke, so the arm is a crystal and not a line with a ring
    // hung off the end of it.
    const barbs = 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < barbs; i++) {
      const at = trunk * ((i + 0.5) / (barbs + 0.8));
      const len = (trunk - at) * (0.2 + rnd() * 0.14);
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * (0.8 + rnd() * 0.3), len, base * 0.7, 0));
      }
    }
    if (rnd() < SPIRAL_CHANCE) {
      parts.push(...spiralVane(rnd, trunk, base));
    }
    return parts;
  }

  /**
   * Builds one arm of a twelve-branched twin: the crystal two hexagonal stars grown
   * through each other make, where the second is turned 30 degrees against the first.
   *
   * The arm itself is a short barbed needle; what makes the habit is that
   * {@link buildShape} replicates it twelve times rather than six, so the long and the
   * short arms alternate around the centre. The trunk is deliberately not full length:
   * twelve arms at 30 degrees each crowd one another at full reach, and the alternation
   * is the silhouette.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius distance from the centre to the edge of the drawing
   * @returns {string[]} the arm's filled elements, trunk first
   */
  /**
   * A ring: one circle with a hole, as a single filled path.
   *
   * The two circles run in opposite directions so the fill leaves the inside empty,
   * which is what lets a figure keep details — eyes, buttons — that a filled disc would
   * swallow.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} outer outer radius, in shape units
   * @param {number} inner hole radius, in shape units
   * @returns {string} the path markup
   */
  function ring(cx, cy, outer, inner) {
    const ro = coord(outer);
    const ri = coord(inner);
    const circle = (r, x, sweep) => "M" + coord(x - r) + " " + coord(cy) +
      "A" + r + " " + r + " 0 1 " + sweep + " " + coord(x + r) + " " + coord(cy) +
      "A" + r + " " + r + " 0 1 " + sweep + " " + coord(x - r) + " " + coord(cy) + "Z";
    return '<path d="' + circle(ro, cx, 0) + circle(ri, cx, 1) + '"/>';
  }

  /**
   * The traditional snowman: three rings of snow, a hat, and arms out flat.
   *
   * This is the one habit that is a figure rather than an arm repeated around a centre,
   * so it is drawn once and it is drawn upright. The arms go out flat, near enough to
   * fill the cell from side to side, because the figure turns about a vertical axis and
   * outstretched arms are what make that turn read as a rotor sweeping rather than as a
   * silhouette widening and narrowing.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius the habit's radius, in shape units
   * @returns {string[]} the figure's path markup
   */
  /**
   * A filled disc with holes punched out of it, as one path.
   *
   * A flake is drawn in one colour, so a detail painted on top of a filled body cannot
   * be seen — the only way to give a filled snowman a face is to take the face out of
   * the body. The holes are subpaths of the same path, wound the other way, so the fill
   * leaves them empty.
   *
   * @param {number} cx centre x in shape units
   * @param {number} cy centre y in shape units
   * @param {number} radius the disc's radius, in shape units
   * @param {Array<Array<number>>} holes holes as [x, y, radius], in shape units, or none
   * @returns {string} the path markup
   */
  function disc(cx, cy, radius, holes) {
    const arc = (x, y, rad, sweep) => "M" + coord(x - rad) + " " + coord(y) +
      "A" + coord(rad) + " " + coord(rad) + " 0 1 " + sweep + " " + coord(x + rad) + " " + coord(y) +
      "A" + coord(rad) + " " + coord(rad) + " 0 1 " + sweep + " " + coord(x - rad) + " " + coord(y) + "Z";
    let d = arc(cx, cy, radius, 0);
    for (const hole of holes || []) {d += arc(hole[0], hole[1], hole[2], 1);}
    return '<path d="' + d + '"/>';
  }

  /**
   * The traditional snowman, drawn once, upright, and turned through the frame strip.
   *
   * He is filled rather than outlined, because at the size a flake is painted at a
   * three-ring stack reads as a target and a filled silhouette reads as a snowman. The
   * turn is baked into the frames: each frame of a slot is the same figure seen one step
   * around a vertical axis, which is what keeps the body one size all the way round. A
   * draw transform that squashed the whole sprite would make him swell and shrink
   * instead of turning, and it would squash the hat and the body along with the arms.
   *
   * What does change with the frame is how far anything sticks out sideways: the arms,
   * the hat, the scarf and the face all live on a plane that swings through the view, so
   * they foreshorten together while the three balls of snow stay put.
   *
   * @param {function(): number} rnd source of randomness
   * @param {number} radius the habit's radius, in shape units
   * @param {number} frame which step of the turn to draw
   * @param {number} frames steps in a full turn
   * @returns {string[]} the figure's path markup
   */
  function armSnowman(rnd, radius, frame, frames) {
    const r = radius;
    const n = Math.max(1, frames || FRAMES);
    const f = Math.max(0, Math.min(n - 1, frame || 0));
    const yaw = f * Math.PI * 2 / n;
    // How much of the sideways extent is still in the plane of the screen: 1 facing us,
    // nothing edge-on. The floor keeps the arms a stub rather than a vanishing line.
    const flat = 0.18 + Math.abs(Math.cos(yaw)) * 0.82;
    // Which half of the turn he is on. The face and the coals are on the front, so they
    // are gone for the back half — and without that the strip is mirror-symmetric, only
    // about five of its sixteen frames differ at all, and the turn reads as a shuffle
    // rather than as a rotation.
    const facing = Math.cos(yaw) > 0;
    const parts = [];
    // Two circles and a hat. That is the whole figure: at the size a flake is painted at,
    // anything finer — a scarf, a carrot, a mouth — is a smudge that costs more than it
    // tells, so the details stop at two eyes and a coal.
    const body = r * (0.4 + rnd() * 0.02);
    const head = body * (0.72 + rnd() * 0.05);
    const yBody = r * 0.42;
    // Overlapped at the neck, so the two circles read as one figure and not as a stack
    // with a gap in it.
    const yHead = yBody - body - head + r * 0.12;
    // Outlined rather than filled: a flake is one colour, so a filled body can only carry
    // its face as holes punched in the fill, and those holes close up at flake size. A
    // ring leaves the inside empty, and the dots drawn in it stay separate dots.
    parts.push(ring(0, yBody, body, body - r * 0.055));
    parts.push(ring(0, yHead, head, head - r * 0.05));
    // Eyes and one coal, and the eyes slide toward the centre line as he turns away.
    if (facing) {
      const eye = r * 0.038;
      const eyeX = head * 0.4 * flat;
      parts.push(dot(-eyeX, yHead - head * 0.12, eye));
      parts.push(dot(eyeX, yHead - head * 0.12, eye));
      parts.push(dot(0, yBody - body * 0.12, r * 0.042));
    }
    // The hat: a brim across the top of the head and a crown standing off it, both level,
    // both keeping their width all the way round — a brim is a disc and a crown is a
    // cylinder, and a thing round about the axis he turns on looks the same from every side.
    const brim = head * (1.3 + rnd() * 0.25);
    parts.push(bar(-brim, yHead - head - r * 0.025, brim * 2, r * 0.045));
    const crown = head * (0.62 + rnd() * 0.08);
    parts.push(bar(-crown, yHead - head - r * 0.28, crown * 2, r * 0.255));
    // Twig arms, level: they hold their height whatever the turn and shorten instead of
    // tilting. One fork each — a stick with a fork in it is a stick, and more than that is
    // noise at this size. Out to the edge of the cell and no further: the arms are the
    // widest thing about him, and past the radius they are drawn into the next cell over.
    const armY = yBody - body * 0.5;
    const edge = Math.sqrt(body * body - (armY - yBody) * (armY - yBody));
    const reach = r * (0.6 + rnd() * 0.05);
    const thick = r * 0.05;
    for (const side of [-1, 1]) {
      const root = side * edge;
      const len = reach * flat;
      parts.push(bar(side < 0 ? root - len : root, armY - thick / 2, len, thick));
      parts.push(spike(root + side * len * 0.7, armY, side * (Math.PI / 2 - 0.75),
                       len * 0.34, r * 0.016, 0));
    }
    return parts;
  }

  function armTwin(rnd, radius) {
    const trunk = radius * (0.62 + rnd() * 0.3);
    const base = radius * (0.026 + rnd() * 0.012);
    const parts = [spike(0, 0, 0, trunk, base, base * 0.25)];
    const barbs = 1 + Math.floor(rnd() * 2);
    for (let i = 0; i < barbs; i++) {
      const at = trunk * ((i + 0.6) / (barbs + 0.7));
      const len = (trunk - at) * (0.3 + rnd() * 0.2);
      const lean = 0.7 + rnd() * 0.3;
      for (const side of [-1, 1]) {
        parts.push(spike(0, -at, side * lean, len, base * 0.8, 0));
      }
    }
    if (rnd() < 0.7) {
      parts.push(stellate(0, -trunk, base * (1.8 + rnd() * 1), rnd() * Math.PI / 3, rnd));
    }
    // The join sweeps 30 degrees here, not 60: the twin habit's neighbour is the next
    // copy of the arm, and there are twelve of them around the centre.
    if (rnd() < SPIRAL_CHANCE) {
      parts.push(...spiralVane(rnd, trunk, base, Math.PI / 6));
    }
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
   * Circumradius of {@link ICOSA}, so a projected z reads as a depth from -1 at the
   * back of the solid to 1 at the front.
   */
  const WIRE_CIRC = ICOSA.reduce((wide, v) =>
    Math.max(wide, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])), 0);

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
  /**
   * The fixed orientation of one wireframe slot's solid, drawn from that slot's seed.
   *
   * The atlas cell and the live painting of a wireframe flake both call this with the
   * slot's seed, so the two agree on which face the solid shows; what differs is only
   * the tumble the flake accumulates as it falls.
   *
   * @param {Function} rnd the slot's own generator
   * @returns {Object} the axis and angles the solid is set at, and the sizes its
   *   drawing uses, the sizes as fractions of the cell radius
   */
  function wireOrientation(rnd) {
    let ux = rnd() * 1.6 - 0.8;
    let uy = rnd() * 1.6 - 0.8;
    let uz = 1 + rnd();
    const un = Math.sqrt(ux * ux + uy * uy + uz * uz);
    return {
      ux: ux / un,
      uy: uy / un,
      uz: uz / un,
      // a fixed lean about x, so the axis is never square to the viewer
      tilt: 0.3 + rnd() * 0.8,
      // the slot's own turn about its axis, so a field of wireframes is not all
      // showing the same face
      spin: rnd() * Math.PI * 2,
      body: 0.72 + rnd() * 0.12,
      core: 0.15 + rnd() * 0.06,
      coreRot: rnd() * Math.PI / 3
    };
  }

  /**
   * The tumble rate one of a wireframe flake's screen axes turns at.
   *
   * @param {function(): number} rnd source of randomness
   * @returns {number} signed radians per step, {@link WIRE_TUMBLE_MIN} to
   *   {@link WIRE_TUMBLE_MAX} away from zero, in either direction
   */
  function tumbleRate(rnd) {
    const rate = WIRE_TUMBLE_MIN + rnd() * (WIRE_TUMBLE_MAX - WIRE_TUMBLE_MIN);
    return rnd() < 0.5 ? -rate : rate;
  }

  /**
   * Projects one wireframe solid into the plane of the screen.
   *
   * @param {Object} solid the orientation from {@link wireOrientation}
   * @param {number} tiltX tumble accumulated about the screen's x axis, in radians
   * @param {number} tiltY tumble accumulated about the screen's y axis, in radians
   * @param {number} radius the cell radius the solid is drawn into, in shape units
   * @returns {Array<Array<number>>} the projected vertices: x and y in shape units,
   *   with z still attached as the depth
   */
  function wireVertices(solid, tiltX, tiltY, radius) {
    const scale = radius * solid.body / WIRE_CIRC;
    const lean = solid.tilt + tiltX;
    const at = [];
    for (const v of ICOSA) {
      const p = rotAxis(v, [solid.ux, solid.uy, solid.uz], solid.spin + tiltY);
      const y = p[1] * Math.cos(lean) - p[2] * Math.sin(lean);
      const z = p[1] * Math.sin(lean) + p[2] * Math.cos(lean);
      // a little perspective: nearer points are pushed out, and it is the change in
      // the outline as the solid turns, more than the size, that reads as depth
      const depth = 1 + z * 0.11 / scale;
      at.push([p[0] * scale * depth, -y * scale * depth, z]);
    }
    return at;
  }

  /**
   * Groups a projected solid's edges and points by how far they sit from the viewer.
   *
   * Every edge is drawn, at a width that follows its depth. Keeping the back half of
   * the solid faint keeps the middle of a sixteen-pixel sprite clear, but doing it with
   * a cut makes lines pop in and out as the solid turns, and a line appearing in the
   * middle of a slow turn is what reads as jank.
   *
   * @param {Array<Array<number>>} at the projected vertices from {@link wireVertices}
   * @returns {Array<Array<Array<Array<number>>>>} one list of line segments per
   *   {@link WIRE_DEPTH} band, from the back of the solid to the front
   */
  function wireBands(at) {
    const groups = WIRE_DEPTH.map(() => []);
    for (const [i, j] of WIRE.stellated) {
      groups[depthBucket((at[i][2] + at[j][2]) / 2)].push([at[i], at[j]]);
    }
    // The points, sharpened: every vertex throws a spike straight out past the body,
    // which is what makes the silhouette read as a stellation rather than as a ball
    // of crossing lines.
    for (const v of at) {
      groups[depthBucket(v[2])].push([v, [v[0] * 1.34, v[1] * 1.34, v[2]]]);
    }
    return groups;
  }

  function armDodeca(rnd, radius) {
    const solid = wireOrientation(rnd);
    const groups = wireBands(wireVertices(solid, 0, 0, radius));
    // A bright centre, as the flat crystals have. Without it the whole drawing is
    // hairlines crossing an empty middle, which reads as a scribble and not as a
    // flake.
    const parts = [stellate(0, 0, radius * solid.core, solid.coreRot, rnd)];
    for (let g = 0; g < groups.length; g++) {
      if (groups[g].length === 0) {continue;}
      let d = "";
      for (const [a, b] of groups[g]) {
        d += "M" + coord(a[0]) + " " + coord(a[1]) + "L" + coord(b[0]) + " " + coord(b[1]);
      }
      parts.push('<path d="' + d + '" fill="none" stroke=":color:" stroke-width="' +
                 coord(WIRE_STROKE_NEAR * WIRE_DEPTH[g]) + '" stroke-linecap="round"/>');
    }
    return parts;
  }

  /**
   * Which stroke width an edge or a point is drawn at, by how far it sits toward
   * the viewer.
   *
   * @param {number} z depth of the edge's midpoint or of the vertex, in
   *   {@link ICOSA}'s own units, so it runs from -{@link WIRE_CIRC} at the back of
   *   the solid to +{@link WIRE_CIRC} at the front
   * @returns {number} index into {@link WIRE_DEPTH}, 0 at the back of the solid
   */
  function depthBucket(z) {
    const front = (z / WIRE_CIRC + 1) / 2;
    const bucket = Math.floor(front * WIRE_DEPTH.length);
    return Math.max(0, Math.min(WIRE_DEPTH.length - 1, bucket));
  }

  /**
   * The arm builders: name, draw weight, builder, whether the habit is a solid rather
   * than a six-fold arm, the radius its crystals take up in the cell as a fraction of
   * the full one, the warp it takes, and how many times the arm is replicated.
   *
   * A dendrite is what snow reads as, so it stays the most common; the rest are
   * there to break up a field of twenty identical silhouettes. Weighted by how well
   * each one still reads at the 11-16 pixels a flake is shown at, measured against a
   * contact sheet of each habit at the atlas cell size and at those two: the needle,
   * sectored, capped and star habits are still legible at 12 pixels, the beaded habit
   * is marginal, and a fine-needle burst and a faceted plate were dropped for
   * collapsing into a blob.
   *
   * The set is drawn from the Magono–Lee classification rather than invented: stellar
   * dendrite, fernlike stellar dendrite, needles, sectored plate, capped column,
   * stellar plate with a ring, hexagonal hollow plate, and the 12-branched twin. The
   * wireframe solid is the one entry that is not a snow-crystal class; it is here
   * because the ice lattice is a stellated polyhedron and it is the only thing in the
   * field with depth.
   *
   * The fifth column shrinks the crystal inside its cell. Six bare columns running
   * the full width of the sprite read as spokes from a wheel, and shrinking the
   * crystal to seven tenths is what makes the needle habit's joined-up spiral arms
   * read as one shape rather than as six separate sticks.
   *
   * The sixth column is the warp that habit takes, as a fraction of
   * {@link WARP_MAX}. A needle's features are small and its arms are thin, so a
   * full-strength twist turns each arm's inner half a quarter turn against its own
   * point and six of those merge into a pinwheel flower; the ring, hollow-plate and
   * twin habits take a gentler twist for the same reason at a smaller scale. The
   * wireframe habit is never warped, so its entry is inert.
   *
   * The seventh column is the replication count. Everything here is six-fold because
   * ice is, except the twelve-branched twin, which is two six-fold crystals grown
   * through each other at a 30 degree twist, and the wireframe solid, which is drawn
   * once because it has no arms to replicate.
   *
   * The eighth column splits the set into the branched crystals that are what a
   * snowflake is taken to look like, and the stem habits — a straight spoke with
   * plates, beads or rings hung on it. The stem habits are drawn smaller on purpose
   * (see the fifth column) and held to {@link STEM_SHARE} of the field: a spoke with
   * three dots on it fills the same cell as a dendrite but reads as a much bigger
   * object, because almost all of it is ink, and a field of twenty of them stops
   * looking like snow and starts looking like a diagram.
   */
  const HABITS = [
    ["dendrite", 30, armDendrite, false, 1, 1, 6, "branch"],
    ["fern", 14, armFern, false, 1, 1, 6, "branch"],
    ["sectored", 11, armSectored, false, 1, 1, 6, "branch"],
    ["star", 9, armStar, false, 1, 1, 6, "branch"],
    ["ringed", 8, armRinged, false, 0.92, 0.7, 6, "branch"],
    ["needle", 9, armNeedle, false, 0.7, 0.45, 6, "stem"],
    ["capped", 6, armCapped, false, 0.82, 1, 6, "stem"],
    ["hexplate", 6, armHexPlate, false, 0.75, 0.6, 6, "stem"],
    ["twin", 5, armTwin, false, 0.82, 0.8, 12, "stem"],
    ["beaded", 4, armBeaded, false, 0.8, 1, 6, "stem"],
    ["dodeca", 8, armDodeca, true, 1, 1, 1, "solid"],
    // The figure habit: drawn once, upright, turning about a vertical axis. It is not a
    // crystal and it is not repeated six times, so it sits outside both the branch and
    // the stem families — see {@link habitSpec}'s figure flag.
    ["snowman", 6, armSnowman, false, 1, 0, 1, "figure", "off"]
  ];

  /**
   * Largest share of the field the stem habits may hold.
   *
   * The weighted draw alone does not enforce this: with eleven habits and twenty
   * slots, a run of weighted picks can land half the field on the stem group, and
   * that is the field that reads as spokes rather than as snow.
   */
  const STEM_SHARE = 0.3;

  /**
   * Turns one {@link HABITS} row into the object {@link buildShape} draws from.
   *
   * @param {Array} habit one row of {@link HABITS}
   * @returns {{name: string, build: Function, wire: boolean, scale: number,
   *   warp: number, copies: number, stem: boolean}} the habit to draw
   */
  function habitSpec(habit) {
    return {name: habit[0], build: habit[2], wire: habit[3], scale: habit[4],
            warp: habit[5], copies: habit[6], stem: habit[7] === "stem",
            figure: habit[7] === "figure"};
  }

  /**
   * Shuffles a list in place (Fisher–Yates) with the caller's generator.
   *
   * @param {Array} list the list to shuffle
   * @param {function(): number} rnd source of randomness
   * @returns {Array} the same list, shuffled
   */
  function shuffle(list, rnd) {
    for (let i = list.length - 1; i > 0; i--) {
      const j = Math.floor(rnd() * (i + 1));
      const swap = list[i];
      list[i] = list[j];
      list[j] = swap;
    }
    return list;
  }

  /**
   * Picks an arm builder from {@link HABITS} by weight.
   *
   * @param {function(): number} rnd source of randomness
   * @returns {{name: string, build: Function, wire: boolean, scale: number,
   *   warp: number, copies: number, stem: boolean}} the habit to draw
   */
  function pickHabit(rnd) {
    const rows = activeHabits();
    let total = 0;
    for (const habit of rows) {total += habit[1];}
    let roll = rnd() * total;
    for (const habit of rows) {
      roll -= habit[1];
      if (roll < 0) {return habitSpec(habit);}
    }
    return habitSpec(rows[rows.length - 1]);
  }

  /**
   * Assembles one whole flake from the arm replicated about the centre — six times
   * for every habit but the twelve-branched twin and the wireframe solid.
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
   * @param {{name: string, build: Function, wire: boolean, scale: number,
   *   warp: number, copies: number}} [habit]
   *   arm builder to use, drawn by weight from {@link HABITS} when not given
   * @param {number} [frame] which of the slot's frames this is
   * @param {number} [frames] frames per slot
   * @returns {string} a complete SVG document
   */
  function buildShape(rnd, size, habit, frame, frames) {
    const picked = habit || pickHabit(rnd);
    const n = Math.max(1, frames || FRAMES);
    // Some habits are drawn smaller than the cell that holds them — see the last
    // column of HABITS. Everything below is measured from this radius, so the whole
    // crystal shrinks together rather than the arms shrinking inside a full-size core.
    const radius = (size / 2 - 1) * (picked.scale || 1);
    const f = Math.max(0, Math.min(n - 1, frame || 0));
    // A flat crystal's frames differ only in where in the warp cycle they sit, so
    // the twist walks through zero and back: the flake untwists, then re-twists the
    // other way. The wireframe habit has no warp — its frames are orientations.
    if (picked.wire) {
      warp = null;
    } else if (rnd() < WARP_CHANCE) {
      const amp = (rnd() - 0.5) * 2 * WARP_MAX * (picked.warp === undefined ? 1 : picked.warp);
      const phase = rnd() * Math.PI * 2;
      warp = {k: amp * Math.sin(phase + f * 2 * Math.PI / n), radius: radius};
    } else {
      warp = null;
    }
    const arm = picked.build(rnd, radius, f, n).join("");
    warp = null;
    // A figure is the whole drawing, so it gets no core: a random stellate burst at the
    // centre of a snowman is a snowman with a star growing out of it.
    const core = !picked.wire && (picked.copies || 6) > 1 && rnd() < 0.75
      ? stellate(0, 0, radius * (0.1 + rnd() * 0.12), rnd() * Math.PI / 3, rnd)
      : "";
    const half = size / 2;
    let uses = "";
    // A wireframe is not six-fold symmetric, so it is drawn once rather than
    // replicated; the replication count is part of the habit, not a guess here.
    const copies = picked.wire || picked.figure ? 1 : (picked.copies || 6);
    const step = 360 / copies;
    for (let k = 0; k < copies; k++) {
      uses += '<use xlink:href="#a"' + (k ? ' transform="rotate(' + step * k + ')"' : "") + "/>";
    }
    return '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" ' +
           'width="' + size + '" height="' + size + '" ' +
           'viewBox="' + coord(-half) + " " + coord(-half) + " " + size + " " + size + '">' +
           '<defs><g id="a" fill=":color:">' + core + arm + "</g></defs>" + uses + "</svg>";
  }

  /**
   * The habit rows the field may draw right now.
   *
   * The page can switch a variant off while it is being reviewed — one checkbox per
   * habit on its tuning panel. A filter that leaves nothing enabled is ignored rather
   * than obeyed: a field with no shapes at all is a blank canvas, and switching the
   * last variant off is more likely a slip than a decision.
   *
   * With no filter at all the field is dealt from {@link defaultHabits}, not from the
   * whole set: the bonus habit stays a bonus until somebody asks for it.
   *
   * @returns {Array<Array>} the enabled rows of {@link HABITS}
   */
  function activeHabits() {
    if (!state.enabled) {return defaultHabits();}
    const on = HABITS.filter(h => state.enabled[h[0]] !== false);
    return on.length ? on : defaultHabits();
  }

  /**
   * The habits the field is dealt from when nobody has said which are in play.
   *
   * The figure habit is marked off by default in its {@link HABITS} row: a snowman is a
   * bonus on the field rather than one of the crystal silhouettes a field is expected to
   * show, so it arrives when the reader presses its tile and not before.
   *
   * @returns {Array<Array>} the rows of {@link HABITS} that are on by default
   */
  function defaultHabits() {
    return HABITS.filter(habit => habit[8] !== "off");
  }

  /**
   * Decides which habit each flake slot draws.
   *
   * The first pass deals one of each habit in a shuffled order, so a field of ten
   * still shows every silhouette rather than whatever seven weighted draws happened
   * to land on. Past that the habit is drawn by weight.
   *
   * The stem habits are held to {@link STEM_SHARE} of the slots in both passes: the
   * deck carries at most that many of them, and a weighted pick that would go past the
   * cap is re-rolled. Twenty slots of spokes-and-beads reads as a diagram, not as snow,
   * however good each one looks on its own.
   *
   * @param {number} count number of flake slots
   * @param {function(): number} rnd source of randomness
   * @returns {Array<Object>} one {@link habitSpec} per slot, in slot order
   */
  function dealSlots(count, rnd) {
    const n = Math.max(1, count | 0);
    const cap = Math.max(1, Math.round(n * STEM_SHARE));
    const rows = activeHabits();
    const deck = shuffle(rows.filter(h => h[7] !== "stem").map(habitSpec), rnd)
      .concat(shuffle(rows.filter(h => h[7] === "stem").map(habitSpec), rnd).slice(0, cap));
    shuffle(deck, rnd);
    let stems = 0;
    const dealt = [];
    for (let i = 0; i < n; i++) {
      let habit = i < deck.length ? deck[i] : pickHabit(rnd);
      // Past the dealt deck the draw is by weight, and a run of weighted picks can put
      // the field back over the cap; re-roll those rather than drop the slot.
      for (let tries = 0; habit.stem && stems >= cap && tries < 16; tries++) {
        habit = pickHabit(rnd);
      }
      if (habit.stem && stems >= cap) {habit = deck.find(h => !h.stem) || habit;}
      if (habit.stem) {stems++;}
      dealt.push(habit);
    }
    return dealt;
  }

  /**
   * Generates {@link FRAMES} shapes per flake slot.
   *
   * This replaces the six hand-drawn files as the shape source: nothing is fetched,
   * and every flake in the field is a different crystal, which costs nothing at draw
   * time because the atlas already holds one cell per frame. Generation runs once at
   * startup, on the worker thread.
   *
   * A slot's frames are built from the same seed, so the crystal is identical across
   * them and only the warp (or the orientation) changes.
   *
   * @param {number} count number of flake slots
   * @param {function(): number} rnd source of randomness
   * @returns {{shapes: string[], wire: boolean[], solids: Array<Object|null>,
   *   specs: Array<Object>}} the shape documents slot-major, which slots are wireframe
   *   solids, their solids, and the habit each slot draws
   */
  function buildSlots(count, rnd) {
    const shapes = [];
    const wire = [];
    // The solid each wireframe slot shows, kept so the flake can be painted as vectors
    // at whatever orientation it has tumbled to instead of being picked off the atlas.
    const solids = [];
    const specs = [];
    // The seed each slot's crystal was built from, kept so the panel can paint its
    // thumbnails from the same draws the field is showing — see slotSeeds.
    const seeds = [];
    for (const habit of dealSlots(count, rnd)) {
      const seed = Math.floor(rnd() * 0x7fffffff);
      specs.push(habit);
      seeds.push(seed);
      wire.push(habit.wire);
      solids.push(habit.wire ? wireOrientation(prng(seed)) : null);
      shapes.push(...buildSlotFrames(seed, habit));
    }
    return {shapes, wire, solids, specs, seeds};
  }

  /**
   * Builds one slot's strip of {@link FRAMES} shapes.
   *
   * @param {number} seed the slot's own seed, shared by all its frames so the crystal
   *   is identical across them and only the warp (or the orientation) changes
   * @param {Object} spec the habit this slot draws, from {@link habitSpec}
   * @returns {string[]} FRAMES complete SVG documents, in frame order
   */
  function buildSlotFrames(seed, spec) {
    const frames = [];
    for (let f = 0; f < FRAMES; f++) {frames.push(buildShape(prng(seed), SHAPE_VIEW, spec, f, FRAMES));}
    return frames;
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
   * The size, in CSS pixels, a flake with this deviation is dealt at right now.
   *
   * Read where the size is written rather than only when a flake is made, so the panel's
   * size sliders change the flakes already falling. The {@link MIN_SIZE} floor is scaled
   * with the multiplier along with everything else: a floor in absolute pixels would pin
   * a shrunken field back to full size.
   *
   * @param {number} sizeDev the flake's own deviation, in [-1, 1]
   * @param {number} [grow] the habit's own multiplier, from {@link habitGrow}; 1 for a
   *   crystal, {@link FIGURE_SIZE} for the figure
   * @returns {number} the dealt size in CSS pixels, at or above the floor
   */
  function flakeSize(sizeDev, grow) {
    return (grow || 1) * state.size * Math.max(MIN_SIZE, BASE_SIZE * (1 + sizeDev * state.sizeVar));
  }

  /**
   * The size multiplier the habit in this atlas slot is dealt at.
   *
   * Read from the slot rather than from the flake, because a refresh can hand a slot to a
   * different habit while a flake keeps drawing from it, and a snowman dealt at crystal
   * size is a snowman nobody can see.
   *
   * @param {number} cell the atlas slot the flake draws from
   * @returns {number} {@link FIGURE_SIZE} for a figure habit, 1 for a crystal
   */
  function habitGrow(cell) {
    const spec = state.specs[cell];
    return spec && spec.figure ? FIGURE_SIZE : 1;
  }

  /**
   * The size a flake has melted down to when it is spent and starts its fall out.
   *
   * Scaled with the panel's size multiplier so a field of small flakes still lives as long
   * as a field of large ones: the removal size is a size, not a lifetime, and leaving it
   * unscaled would kill every flake the moment the multiplier dropped below it. The habit's
   * own multiplier applies here too, so a figure melts by the same proportion as the crystal
   * beside it rather than shrinking to a crystal's endpoint.
   *
   * @param {number} [grow] the habit's own multiplier, from {@link habitGrow}
   * @returns {number} the removal size in CSS pixels
   */
  function removalSize(grow) {
    return (grow || 1) * REMOVAL_SIZE * state.size;
  }

  /**
   * Creates one flake in a random position inside {@link bounds}, taking its
   * shape and colour from the atlas slot at {@link cell}.
   *
   * @param {Object} bounds {width, height} of the viewport in CSS pixels
   * @param {number} cell atlas slot this flake draws from
   * @param {function(): number} rnd source of randomness
   * @param {boolean} [wire] true when this slot is a wireframe habit, whose solid is
   *   painted as vectors at the orientation the flake has tumbled to
   * @param {Object} [solid] the solid that slot shows, from {@link wireOrientation}
   * @returns {Object} the flake's mutable state
   */
  function createFlake(bounds, cell, rnd, wire, solid) {
    // How far this flake sits off the field's size, in [-1, 1], drawn once and kept for
    // its whole life the way the gravity and spin deviations are. The size-variation
    // setting says how much of it counts, so moving that slider re-spreads the field that
    // is already falling instead of waiting for the next respawn.
    const sizeDev = rnd() * 2 - 1;
    const size = flakeSize(sizeDev, habitGrow(cell));
    const flake = {
      cell,
      // Falls this flake has lived through. The simulation watches it change to catch
      // the step a flake was recycled on, which is the only step on which the crystal
      // it draws may change — see {@link refreshCrystal}.
      life: 0,
      sizeDev,
      initialSize: size,
      size,
      x: rnd() * bounds.width,
      y: rnd() * bounds.height - size,
      speed: 0.3 + (size / 17) * 0.6,
      vy: 0.3 + (size / 17) * 0.6,
      // How far this flake sits off the field's gravity, in [-1, 1]. The panel's
      // gravity-variation setting multiplies this; at 0 every flake falls at the
      // configured rate and at 1 the field spreads across the whole range. The size
      // term above is the flake's own weight and stays whatever the setting says.
      speedDev: rnd() * 2 - 1,
      vx: 0,
      initialOpacity: rnd() * 0.2 + 0.8,
      opacity: 0,
      opacityPhase: rnd() * Math.PI * 2,
      opacitySpeed: rnd() * 0.075 + 0.015,
      rotation: rnd() * Math.PI * 3,
      // Both directions, so a field of flakes does not read as one pinwheel. The
      // sign is the flake's own and the death spin inherits it; spinSpeed is what
      // keeps the magnitude off zero rather than this clamp keeping it positive.
      // This is also the deviation the panel's spin-variation setting scales: at its
      // default of 1 the field turns at the whole spread it has always been dealt.
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
      // A wireframe flake carries its solid's orientation and the tumble it has
      // accumulated about the two screen axes. The z axis is the sprite's own rotation,
      // which the draw transform already turns smoothly; these two are what the live
      // painting of the solid needs, and they advance every step, so the solid turns
      // continuously on all three axes instead of being flipped between poses.
      solid: solid || null,
      tiltX: rnd() * Math.PI * 2,
      tiltY: rnd() * Math.PI * 2,
      spinX: tumbleRate(rnd),
      spinY: tumbleRate(rnd)
    };
    flake.opacity = flake.initialOpacity;
    // A figure habit — the snowman — is not a crystal: it has a top, and it keeps it.
    // The turn it makes is a yaw about its own vertical axis, which the draw transform
    // shows as a horizontal sweep rather than as a rotation, and the rock is the few
    // degrees it leans off vertical as the wind takes it.
    const spec = state.specs[cell];
    flake.figure = !!(spec && spec.figure);
    if (flake.figure) {
      flake.yawPhase = rnd() * Math.PI * 2;
      // Radians per step: about one turn in 3-5 seconds, which is slow enough that the
      // arms read as sweeping rather than as a strobe.
      flake.yawSpeed = (0.02 + rnd() * 0.014) * (rnd() < 0.5 ? -1 : 1);
      flake.rock = rnd() * Math.PI * 2;
      flake.rockSpeed = 0.02 + rnd() * 0.02;
    }
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
    flake.life++;
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
   * A wireframe flake is geared into its own band, {@link WIRE_SPIN_MIN} to
   * {@link WIRE_SPIN_MAX}. It is the only habit that reads as a solid rather than as
   * a flat drawing, and a solid turning at a flat crystal's rate reads as a drill;
   * its silhouette is twelve-pointed too, which aliases at half the rate a six-fold
   * one does.
   *
   * A flake that is falling slowly — under {@link SLOW_FALL_SPEED} a step, which is
   * the smallest third of the field — ramps up as it travels, from its own rate at the
   * top of the screen to {@link SLOW_SPIN_RAMP} times it at the bottom. Without the
   * ramp those flakes barely turn at all, and a field of them has one spin rate in it
   * rather than a spread.
   *
   * @param {Object} flake the flake to read
   * @returns {number} signed radians per step, never nearer zero than
   *   {@link WIRE_SPIN_MIN} for a wireframe flake and {@link MIN_SPIN} for the rest
   */
  function spinSpeed(flake) {
    // The panel's spin variation scales this rate's own magnitude: at 1 every flake turns
    // at the rate it was dealt, which is the spread the field has always had, and at 0 the
    // dealt rates are switched off and the field turns at the floor below.
    let speed = flake.rotationSpeed * state.spinVar * Math.max(1, flake.initialSize / flake.size);
    if (!flake.wire && flake.speed <= SLOW_FALL_SPEED && flake.maxDistance > 0) {
      const travelled = Math.min(1, flake.totalDistance / flake.maxDistance);
      speed *= 1 + SLOW_SPIN_RAMP * travelled;
    }
    const floor = flake.wire ? WIRE_SPIN_MIN : MIN_SPIN;
    const limit = flake.wire ? WIRE_SPIN_MAX : Infinity;
    const geared = Math.min(limit, Math.abs(speed)) * (speed < 0 ? -1 : 1);
    return geared < 0 ? Math.min(-floor, geared) : Math.max(floor, geared);
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
   * @returns {number} signed radians per step, bounded by {@link DEATH_SPIN_MAX} for
   *   a flat crystal and {@link WIRE_DEATH_SPIN_MAX} for a wireframe one
   *   times the wobble factor
   */
  function deathSpinAt(flake) {
    const sign = flake.spinEnv < 0 ? -1 : 1;
    const ceiling = flake.wire ? WIRE_DEATH_SPIN_MAX : DEATH_SPIN_MAX;
    flake.spinEnv = sign * Math.min(ceiling, Math.abs(flake.spinEnv) * DEATH_SPIN_GROWTH);
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
  /**
   * How far off vertical a figure habit may lean, in radians.
   *
   * A snowman rocks; it does not turn over. Past this the figure reads as falling head
   * over heels, which is what the crystal physics does to a shape that has no top.
   */
  const FIGURE_ROCK = 0.06;

  /**
   * Sets a figure flake's lean and advances its yaw.
   *
   * Both are driven off phases rather than rates, so the lean is bounded: the figure
   * swings through a few degrees and back, the way a helicopter model hangs on a string,
   * and the yaw runs steadily so the outstretched arms sweep in and out.
   *
   * @param {Object} flake the figure flake
   * @param {number} spin the field's spin multiplier, with the flake's spread in it
   * @returns {void}
   */
  function figureTurn(flake, spin) {
    flake.rock += flake.rockSpeed * spin;
    flake.yaw += flake.yawSpeed * spin;
    flake.rotation = Math.sin(flake.rock) * FIGURE_ROCK;
  }

  function stepFlake(flake, bounds, rnd) {
    // The panel's gravity and spin settings are multipliers read where the motion is
    // applied, not at the flake's creation, so moving a slider moves the flakes already
    // on the field rather than only the next ones in. Each flake carries its own
    // deviation, drawn when it was made, which is what the variation settings widen.
    const gravity = state.gravity * (1 + (flake.speedDev || 0) * state.gravityVar);
    const spin = state.spin;
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
      if (flake.figure) {figureTurn(flake, spin);} else {
        flake.rotation += scaleSpin(flake.deathSpinSpeed, spin, DEATH_SPIN_MAX) + flake.gustSpin;
      }
      // A flake tumbling fast does not fall along a straight line: a little of the
      // spin comes back out as sideways travel, which is what makes the path read
      // as a spiral rather than as a spin in place.
      flake.x += flake.driftVx +
                 Math.sin(flake.rotation) * flake.deathSpinSpeed * SPIN_LATERAL + flake.gustVx;
    } else {
      flake.windPhase += WIND_STEP;
      flake.vx = Math.sin(flake.windPhase) * flake.windSpeed;
      flake.driftVx = flake.vx;
      if (flake.figure) {figureTurn(flake, spin);} else {
        flake.rotation += scaleSpin(spinSpeed(flake), spin, flake.wire ? WIRE_SPIN_MAX : Infinity) +
                          flake.gustSpin;
      }
      flake.x += flake.vx + flake.gustVx;
    }
    flake.y += flake.vy * gravity + flake.gustVy;
    flake.totalDistance += flake.vy * gravity;

    // Which frame of the slot's strip is showing: a flat crystal walks its warp cycle
    // on a slow timer. A wireframe's frames are all one pose, because its motion is not
    // in the atlas at all — the solid is painted as vectors at the orientation below.
    flake.steps++;
    if (flake.figure) {
      // A figure's frames are steps of one turn about its own vertical axis, so walking
      // the strip is the turn. It is driven off spin so the panel's spin control turns
      // him, and the frame index is kept in range by the strip's own length.
      flake.yawPhase = (flake.yawPhase || 0) + flake.yawSpeed * spin;
      flake.frame = Math.round(flake.yawPhase / (Math.PI * 2 / FRAMES)) % FRAMES;
      if (flake.frame < 0) {flake.frame += FRAMES;}
    } else {
      flake.frame = Math.floor(flake.steps / FRAME_BEATS) % FRAMES;
    }
    // The solid's tumble about the two screen axes, advanced every step so it turns
    // continuously rather than being flipped between poses. The third axis is the
    // sprite's own rotation, which the draw transform already turns smoothly.
    if (flake.wire) {
      flake.tiltX += scaleSpin(flake.spinX, spin, WIRE_TUMBLE_MAX);
      flake.tiltY += scaleSpin(flake.spinY, spin, WIRE_TUMBLE_MAX);
    }

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
    // The habit's own multiplier, read once: the melt endpoint is scaled by it as well as
    // the size it started at, so a figure shrinks by the same proportion as a crystal.
    const spent = removalSize(habitGrow(flake.cell));
    flake.size = flake.initialSize - (flake.initialSize - spent + 0.4) * ease;
    // And dim by three tenths over the journey, not by more than that: past this the
    // fade-out to a respawn is a blink, not ageing.
    flake.opacity = flake.initialOpacity * (1 - 0.3 * travelled);

    // The flicker rides on top of the envelope as a bounded multiplier, so it can
    // never move the size or push the opacity across a threshold of its own.
    flake.opacityPhase += flake.opacitySpeed;
    flake.opacity *= 0.95 + Math.sin(flake.opacityPhase) * 0.05 + (rnd() - 0.5) * 0.03;

    if (flake.size < spent) {
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
    // The habit each slot draws, kept so a refresh can replace one slot's crystal
    // without re-dealing the field and changing every other slot.
    specs: [],
    // The seed each slot's crystal was built from, index-for-index with specs, so the
    // panel can paint its thumbnails from the same draws the field is showing.
    seeds: [],
    flakes: [],
    count: 0,
    base: 0,
    offscreen: false,
    canvas: null,
    ctx: null,
    atlas: null,
    // The atlas's own context, kept because a refresh patches single cells in the atlas
    // rather than rebuilding it, and getContext() on an OffscreenCanvas hands back the
    // same context only if it was already created — which is what rasterAtlas did.
    atlasCtx: null,
    layout: null,
    // The base colour as rgb, with the panel's hue rotation already applied, so a
    // refreshed slot is coloured the same way buildPalette coloured the rest.
    rgb: null,
    // The theme colour as the page sent it, before any rotation.
    baseRgb: null,
    // Hue rotation the panel has asked for, in degrees; 0 is the theme colour.
    hue: 0,
    // Whether the panel's colour cycle is running.
    cycle: false,
    // How far the cycle has moved the palette so far, in degrees. Added to the hue
    // slider rather than replacing it, so the control still says where the field is.
    cycleHue: 0,
    // Fixed steps since the cycle last re-sent the atlas to a page that rasterizes it.
    cycleSteps: 0,
    // Motion multipliers the panel has asked for. 1 is the field as it was dealt; the
    // variation settings are fractions of each flake's own deviation, so 0 makes the
    // field move as one flake and 1 spends the whole range it was dealt.
    gravity: 1,
    gravityVar: DEFAULT_GRAVITY_VAR,
    spin: 1,
    spinVar: DEFAULT_SPIN_VAR,
    // Size multiplier and per-flake size spread, read where a flake's size is written
    // rather than only when it is made — see setSize. The spread is a fraction of each
    // flake's own deviation from BASE_SIZE, exactly as the gravity and spin spreads are.
    size: DEFAULT_SIZE,
    sizeVar: DEFAULT_SIZE_VAR,
    // How far an individual crystal may sit off that hue, 0 to 1, as a fraction of
    // {@link HUE_SPREAD_MAX}. The default is deliberately small: a field of snow is one
    // colour, and the control exists to widen that, not to start there.
    variation: 0.15,
    // Crystals replaced so far, reported in the stats message so the page can show that
    // an open field is still producing new shapes rather than looping the first twenty.
    refreshes: 0,
    // Chance a recycled flake draws a new crystal, set from {@link CRYSTAL_REFRESH_CHANCE}
    // at init and adjustable by the page — see the refresh message.
    refreshChance: CRYSTAL_REFRESH_CHANCE,
    // Which variants the page has switched on, keyed by habit name; null means every
    // variant in HABITS is in play.
    enabled: null,
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
    for (let i = 0; i < state.shapes.length; i++) {
      drawCell(ctx, i, state.palette[Math.floor(i / FRAMES) % state.palette.length].color);
    }
    state.atlas = canvas;
    state.atlasCtx = ctx;
    return true;
  }

  /**
   * Rasterizes one shape into its atlas cell.
   *
   * @param {OffscreenCanvasRenderingContext2D} ctx the atlas context
   * @param {number} index cell index, slot-major and frame-minor
   * @param {string} color the fill and stroke colour this slot's crystals are drawn in
   * @returns {void}
   */
  function drawCell(ctx, index, color) {
    const scale = CELL / SHAPE_VIEW;
    const shape = shapePaths(state.shapes[index]);
    ctx.save();
    ctx.translate((index % state.layout.cols) * CELL + CELL / 2,
                  Math.floor(index / state.layout.cols) * CELL + CELL / 2);
    ctx.scale(scale, scale);
    ctx.lineCap = "round";
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

  /**
   * Re-draws one slot's {@link FRAMES} cells in the atlas.
   *
   * The cells are cleared before they are drawn again: the atlas is patched, not
   * rebuilt, so the ink the old crystal left inside a cell would still be sitting
   * behind the new one.
   *
   * @param {number} slot the slot whose cells are stale
   * @returns {void}
   */
  function repaintCells(slot) {
    const ctx = state.atlasCtx;
    if (!ctx) {return;}
    const color = state.palette[slot] ? state.palette[slot].color : "#87ceeb";
    for (let f = 0; f < FRAMES; f++) {
      const i = slot * FRAMES + f;
      const col = (i % state.layout.cols) * CELL;
      const row = Math.floor(i / state.layout.cols) * CELL;
      ctx.clearRect(col, row, CELL, CELL);
      drawCell(ctx, i, color);
    }
  }

  /**
   * Re-draws every cell in the atlas.
   *
   * A change that touches all of them at once — the palette's hue, for one — cannot be
   * patched a slot at a time, and at this atlas size the whole repaint costs about the
   * same as a dozen slots would.
   *
   * @returns {void}
   */
  function repaintAllCells() {
    for (let slot = 0; slot < state.specs.length; slot++) {repaintCells(slot);}
  }

  /**
   * Gives a recycled flake a crystal its slot has never held.
   *
   * The slot's whole frame strip is rebuilt from one fresh seed and its atlas cells are
   * painted over, so the flake falls with a new shape and every later flake that draws
   * from that slot gets the same one. A wireframe slot's solid is replaced with the one
   * the new habit shows, and the flake's colour is re-varied, which is the other half of
   * "the field keeps changing" — a set of crystals that only changes shape half the time
   * looks like it is changing shape half the time.
   *
   * The stem habits stay a minority: a refresh that would put the field back over
   * {@link STEM_SHARE} is re-rolled rather than applied, because a slow drift through
   * refreshes would otherwise undo the cap the deck enforces.
   *
   * Only the worker-rasterized atlas can be patched in place. In the fallback the page
   * holds the bitmap it rasterized from the ready message, and replacing that means a
   * second handshake and a race with the frames in flight, so there the field keeps the
   * crystals it started with.
   *
   * @param {Object} flake the flake that has just been recycled; it draws the new
   *   crystal from its next fall
   * @returns {boolean} true when the slot was replaced
   */
  function refreshCrystal(flake) {
    if (!state.rasterized || !state.atlasCtx) {return false;}
    const spec = refreshSpec(state.specs, state.rnd);
    if (!spec) {return false;}
    dealSlot(flake.cell, spec);
    flake.wire = spec.wire;
    flake.solid = state.solids[flake.cell];
    flake.frame = 0;
    flake.steps = 0;
    // The new habit may be drawn at a different size than the one it replaced — the
    // figure is dealt bigger than a crystal — and a flake keeps its size across a
    // recycle, so the slot's multiplier is applied here rather than only when the
    // flake was first made. The step recomputes the melt curve from this value.
    flake.initialSize = flakeSize(flake.sizeDev || 0, habitGrow(flake.cell));
    return true;
  }

  /**
   * Chooses the habit a slot takes over, or null when no replacement fits.
   *
   * The stem habits are held to {@link STEM_SHARE} of the slots here exactly as the
   * dealt deck holds them: the deck's cap only covers the field as first dealt, and a
   * refresh that lands on a stem habit every time would drift a capped field back to
   * uncapped within a few minutes of snow.
   *
   * @param {Array<Object>} specs the habits the field's slots draw now
   * @param {function(): number} rnd source of randomness
   * @returns {Object|null} the habit to deal, or null when every draw was a stem habit
   *   the field has no room for
   */
  function refreshSpec(specs, rnd) {
    const cap = Math.max(1, Math.round(specs.length * STEM_SHARE));
    let stems = 0;
    for (const spec of specs) {
      if (spec.stem) {stems++;}
    }
    for (let tries = 0; tries < 8; tries++) {
      const pick = dealSlots(1, prng(Math.floor(rnd() * 0x7fffffff)))[0];
      if (pick.stem && stems >= cap) {continue;}
      return pick;
    }
    return null;
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
   * The habit each slot draws right now.
   *
   * The page's panel uses this to show which variants the live field holds, and the
   * tests use it to check that switching a variant out really did reach the field.
   *
   * @returns {string[]} one habit name per slot, in slot order; empty before init
   */
  function fieldSpecs() {
    return state.specs.map(spec => spec.name);
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
    // Zero is allowed: the panel's slider runs down to nothing, and an empty field is a
    // field the reader can bring back rather than a broken page. Past the dealt slot
    // count the flakes share crystals, which is what asking for more snow than the
    // atlas holds looks like.
    const want = Math.max(0, Math.min(TUNING_MAX_FLAKES, count | 0));
    // What the page asked for is kept even when nothing is in play, so switching a
    // variant back on brings the same number of flakes back rather than a stub field.
    state.count = want;
    if (!state.dirtyRects || state.dirtyRects.length < want * 4) {
      state.dirtyRects = new Float32Array(Math.max(1, want) * 4);
    }
    const live = anyOn() ? want : 0;
    while (state.flakes.length > live) {state.flakes.pop();}
    while (state.flakes.length < live) {
      const slot = state.flakes.length % state.palette.length;
      state.flakes.push(createFlake(state.bounds, slot, state.rnd, state.wire[slot],
                                    state.solids[slot]));
    }
  }

  /**
   * Switches variants in and out of the field.
   *
   * A variant that goes off goes now: every slot drawing it is re-dealt and the flakes
   * standing on those slots are replaced with new ones, so the reviewer sees the field
   * without it instead of waiting out a fall. A variant that comes back is dealt into
   * slots taken from whichever habit the field holds most of, so it shows up on this
   * field rather than only after a restart. Everything else keeps its crystal, colour
   * and drift — the point of the panel is to compare one habit against the rest, and a
   * field that re-drew itself every time a box was ticked would make that impossible.
   *
   * @param {Object|null} enabled habit name to boolean; null puts every variant back in
   *   play
   * @returns {void}
   */
  function setHabits(enabled) {
    const was = state.enabled;
    state.enabled = enabled && typeof enabled === "object" ? enabled : null;
    if (!state.specs.length) {return;}
    if (!anyOn()) {
      // Every variant is off, so there is nothing to deal and nothing to draw: the
      // field is empty now, not a field that finishes falling and then empties.
      state.flakes.length = 0;
      state.dirtyAll = true;
      return;
    }
    const stale = [];
    for (let i = 0; i < state.specs.length; i++) {
      if (!isOn(state.specs[i].name)) {stale.push(i);}
    }
    // Variants that were off and are on now: each one needs a slot of its own.
    const back = [];
    for (const habit of HABITS) {
      const name = habit[0];
      if (isOn(name) && was && was[name] === false) {back.push(name);}
    }
    const touched = new Set(stale);
    /** Habits this pass has already dealt, so freed slots spread out instead of piling up. */
    const taken = new Set();
    for (let i = stale.length; i < back.length; i++) {
      const donor = largestHabitSlot(touched);
      if (donor < 0) {break;}
      touched.add(donor);
    }
    if (!touched.size) {return;}
    let at = 0;
    for (const slot of touched) {
      const named = at < back.length ? back[at] : null;
      at++;
      // A replacement crystal is drawn in the field's own proportions, and one habit
      // taking every freed slot is not proportions — it is a pile-up, and it is what makes
      // a toggle look like the field collapsed onto one shape. Re-roll a repeat.
      const spec = named ? habitSpec(HABITS.find(h => h[0] === named)) : freshSpec(taken);
      taken.add(spec && spec.name);
      dealSlot(slot, spec || refreshSpec(state.specs, state.rnd));
    }
    // The flakes that were drawing those slots are gone and new ones take their place,
    // which is also what puts the returning variants on screen straight away.
    for (let i = 0; i < state.flakes.length; i++) {
      if (!touched.has(state.flakes[i].cell)) {continue;}
      const cell = state.flakes[i].cell;
      state.flakes[i] = createFlake(state.bounds, cell, state.rnd, state.wire[cell], state.solids[cell]);
    }
    // A field that was emptied by switching everything off has no flakes to replace, so
    // the flakes that come back with the first variant are added here, at the count the
    // page asked for before it cleared the field.
    if (state.flakes.length < state.count) {setCount(state.count);}
    state.dirtyAll = true;
    sendCells();
    sendSlots();
  }

  /**
   * A crystal to put in a slot whose habit was just switched off.
   *
   * Drawn the way a rolling refresh draws one — weighted over the variants still in play,
   * and held under the field's stem cap — but skipping any habit this same pass has
   * already used, so switching two variants off does not hand both slots to one shape.
   *
   * @param {Set<string>} used habit names already dealt by this pass
   * @returns {Object|null} the habit spec to deal, or null when nothing can be drawn
   */
  function freshSpec(used) {
    for (let tries = 0; tries < 8; tries++) {
      const pick = refreshSpec(state.specs, state.rnd);
      if (pick && !used.has(pick.name)) {return pick;}
    }
    return refreshSpec(state.specs, state.rnd);
  }

  /**
   * Whether any variant is in play.
   *
   * @returns {boolean} false when the panel has switched every variant off
   */
  function anyOn() {
    return HABITS.some(habit => isOn(habit[0]));
  }

  /**
   * Whether a variant is in play.
   *
   * @param {string} name the habit's name
   * @returns {boolean} true when the panel has not switched it off
   */
  function isOn(name) {
    return !state.enabled || state.enabled[name] !== false;
  }

  /**
   * The slot holding a member of the habit the field has most of.
   *
   * @param {Set<number>} skip slots already being re-dealt
   * @returns {number} a slot to take a crystal from, or -1 when none is left
   */
  function largestHabitSlot(skip) {
    const counts = new Map();
    for (let i = 0; i < state.specs.length; i++) {
      if (skip.has(i)) {continue;}
      const name = state.specs[i].name;
      counts.set(name, (counts.get(name) || 0) + 1);
    }
    let best = -1;
    let bestCount = 0;
    for (let i = 0; i < state.specs.length; i++) {
      if (skip.has(i)) {continue;}
      if ((counts.get(state.specs[i].name) || 0) > bestCount) {
        best = i;
        bestCount = counts.get(state.specs[i].name) || 0;
      }
    }
    return best;
  }

  /**
   * Gives one slot a new crystal: its habit, its frame strip, its solid, its colour and
   * the atlas cells that hold it.
   *
   * @param {number} slot the slot to re-deal
   * @param {Object|null} spec the habit to deal, from {@link habitSpec}
   * @returns {void}
   */
  function dealSlot(slot, spec) {
    if (!spec) {return;}
    const seed = Math.floor(state.rnd() * 0x7fffffff);
    state.specs[slot] = spec;
    state.seeds[slot] = seed;
    state.shapes.splice(slot * FRAMES, FRAMES, ...buildSlotFrames(seed, spec));
    state.wire[slot] = spec.wire;
    state.solids[slot] = spec.wire ? wireOrientation(prng(seed)) : null;
    if (state.palette[slot]) {
      const cell = state.palette[slot];
      cell.delta = varyDelta(state.rgb, 10, state.rnd);
      cell.spread = state.rnd() * 2 - 1;
      cell.color = cellColor(cell);
    }
    if (state.atlasCtx) {repaintCells(slot);}
  }

  /**
   * One entry per habit the field's slots draw, as [name, seed], the seed being the one
   * that built that habit's first crystal in the field.
   *
   * The panel paints one thumbnail per habit. Painted from a seed of its own, a thumbnail
   * is a different crystal from the one the field is drawing under that name, and it keeps
   * showing the old one while a reseed, a switched-off variant or a rolling refresh moves
   * the field to a new set. This is what the panel redraws from.
   *
   * @returns {Array<Array>} [habit name, slot seed] pairs, first slot per habit
   */
  function slotSeeds() {
    const first = new Map();
    for (let i = 0; i < state.specs.length; i++) {
      if (!first.has(state.specs[i].name)) {first.set(state.specs[i].name, state.seeds[i]);}
    }
    return Array.from(first, ([name, seed]) => [name, seed]);
  }

  /**
   * Posts the field's current per-habit seeds to the page.
   *
   * Sent wherever the field's crystals are dealt wholesale — a reseed, a variant switched
   * on or off — because the panel redraws its thumbnails from them, and waiting for the
   * next stats message would leave the old crystals on the card for up to two seconds
   * after the field changed.
   *
   * @returns {void}
   */
  function sendSlots() {
    send({type: "slots", slots: slotSeeds()});
  }

  /**
   * Sets how often a replacement flake draws a brand-new crystal.
   *
   * @param {number} chance probability in [0, 1]; anything else restores
   *   {@link CRYSTAL_REFRESH_CHANCE}
   * @returns {void}
   */
  function setRefreshChance(chance) {
    const want = Number(chance);
    state.refreshChance = Number.isFinite(want) ? Math.max(0, Math.min(1, want)) : CRYSTAL_REFRESH_CHANCE;
  }

  /**
   * Rotates the field's palette about its hue.
   *
   * @param {number} degrees rotation on the colour wheel; anything else is ignored
   * @returns {void}
   */
  function setHue(degrees) {
    const want = Number(degrees);
    if (!Number.isFinite(want)) {return;}
    state.hue = ((want % 360) + 360) % 360;
    applyHue();
  }

  /**
   * The colour one slot draws in.
   *
   * The theme colour, turned by the panel's hue, pushed off it by the slot's own share
   * of the variation, and then given the slot's small channel offset. The slot's share
   * was fixed when it was dealt, so moving either control moves the field as a field:
   * the same crystals in a different colour, rather than a reshuffle of which flake is
   * which shade.
   *
   * @param {Object} cell the palette entry for the slot
   * @returns {string} the colour as rgb(r,g,b)
   */
  function cellColor(cell) {
    const shift = state.hue + state.cycleHue + (cell.spread || 0) * state.variation * HUE_SPREAD_MAX;
    return colorText(rotateHue(state.baseRgb, shift), cell.delta);
  }

  /**
   * Applies the field's spin multiplier and spread to one rotation rate.
   *
   * The clamp is not decoration. Every spin rate in the field already carries a ceiling
   * set by how fast a sprite of that symmetry can be sampled before it aliases, and a
   * spread that pushed a flake past its own ceiling buys a shimmer, not a faster turn.
   *
   * @param {number} rate the rate as the physics computed it, signed
   * @param {number} factor the field's spin multiplier, with this flake's spread in it
   * @param {number} limit the largest magnitude this rate may reach
   * @returns {number} the scaled rate, inside the limit
   */
  function scaleSpin(rate, factor, limit) {
    const scaled = rate * factor;
    return scaled < 0 ? -Math.min(-scaled, limit) : Math.min(scaled, limit);
  }

  /**
   * Sets the field's motion multipliers and how far individual flakes may sit off them.
   *
   * Read where the motion is applied rather than when a flake is made, so a slider move
   * changes the flakes already falling. Each flake carries its own deviation, drawn when
   * it was created; the variation settings say how much of that deviation counts, which
   * is why gravity at 0 makes the field fall as one sheet. The spin setting is the range
   * of that deviation per crystal, and its default of 1 is the spread the field has always
   * been dealt — the setting narrows it below that, or widens it past it.
   *
   * @param {Object} msg the page's motion message; anything it leaves out is unchanged
   * @param {number} msg.gravity fall-rate multiplier, 1 is the field as dealt
   * @param {number} msg.gravityVar per-flake gravity spread, 0 to 1
   * @param {number} msg.spin rotation-rate multiplier, 0 stops the spin
   * @param {number} msg.spinVar per-crystal spin deviation range, 0 to {@link SPIN_VAR_MAX}
   * @returns {void}
   */
  function setMotion(msg) {
    state.gravity = clampSetting(msg.gravity, state.gravity, MOTION_MIN, MOTION_MAX);
    state.gravityVar = clampSetting(msg.gravityVar, state.gravityVar, 0, 1);
    state.spin = clampSetting(msg.spin, state.spin, 0, MOTION_MAX);
    state.spinVar = clampSetting(msg.spinVar, state.spinVar, 0, SPIN_VAR_MAX);
  }

  /**
   * Sets the field's size multiplier and the per-flake spread on it, and re-deals the
   * sizes of the flakes that are already falling.
   *
   * The same shape as {@link setMotion}: each flake carries its own deviation from the
   * field's size, drawn when it was created, and the spread setting says how much of that
   * deviation counts. The flakes' current sizes are recomputed here as well as the sizes
   * they are dealt at, because a paused field takes no step to do it and a slider that
   * does nothing until the field is falling again reads as a broken control.
   *
   * @param {Object} msg the page's size message; anything it leaves out is unchanged
   * @param {number} msg.scale field size multiplier, 1 is the field as dealt
   * @param {number} msg.variation per-flake size spread, 0 to 1
   * @returns {void}
   */
  function setSize(msg) {
    state.size = clampSetting(msg.scale, state.size, SIZE_MIN, SIZE_MAX);
    state.sizeVar = clampSetting(msg.variation, state.sizeVar, 0, 1);
    for (const flake of state.flakes) {
      const grow = habitGrow(flake.cell);
      flake.initialSize = flakeSize(flake.sizeDev || 0, grow);
      if (flake.isDying) {continue;}
      // The same curve the step uses, so a flake that has already begun to melt keeps its
      // place on that curve instead of snapping back to full size.
      const travelled = flake.maxDistance > 0 ? Math.min(1, flake.totalDistance / flake.maxDistance) : 0;
      flake.size = flake.initialSize - (flake.initialSize - removalSize(grow) + 0.4) * travelled * travelled;
    }
  }

  /**
   * Clamps one panel setting, leaving the current value in place when the page sent no
   * number at all — a motion message may carry only the one slider that moved.
   *
   * @param {*} value what the page sent
   * @param {number} current the setting as it stands
   * @param {number} min lowest accepted value
   * @param {number} max highest accepted value
   * @returns {number} the value to use
   */
  function clampSetting(value, current, min, max) {
    const want = Number(value);
    return Number.isFinite(want) ? Math.max(min, Math.min(max, want)) : current;
  }

  /**
   * Turns the colour cycle on or off.
   *
   * Switching it off puts the palette back where the hue slider says it is, rather than
   * leaving the field stopped at whatever point of the wheel the cycle happened to be at.
   *
   * @param {boolean} on whether the palette keeps moving
   * @returns {void}
   */
  function setCycle(on) {
    state.cycle = !!on;
    state.cycleSteps = 0;
    if (!state.cycle) {state.cycleHue = 0;}
    applyHue();
  }

  /**
   * Moves the cycle along for the steps just simulated and repaints part of the atlas.
   *
   * @param {number} steps fixed steps the simulation just ran
   * @returns {void}
   */
  function advanceCycle(steps) {
    if (!state.cycle || !state.palette.length) {return;}
    state.cycleHue = (state.cycleHue + steps * CYCLE_DEGREES_PER_SEC * FRAME_MS / 1000) % 360;
    for (const cell of state.palette) {cell.color = cellColor(cell);}
    if (!state.atlasCtx) {
      // The page owns the atlas in this mode and repainting it costs a decode of every
      // shape, so the cycle sends it once a second of simulated time there. A colour
      // drift that needs a second to land is not one anyone is watching.
      state.cycleSteps += steps;
      if (state.cycleSteps < CYCLE_PAGE_STEPS) {return;}
      state.cycleSteps = 0;
      sendCells();
      return;
    }
    const slots = state.specs.length;
    if (!slots) {return;}
    // Repaint the cells the field is actually showing, once per painted beat, so every
    // flake changes colour on the same beat it is drawn on. Repainting a slot's whole
    // frame strip instead — the form this had — left each flake holding one colour until
    // the walk reached it, which reads as a wave crossing the field rather than as one
    // colour moving; and repainting every strip every beat costs FRAMES times the work
    // for frames nobody is looking at.
    const seen = new Set();
    for (const flake of state.flakes) {
      const at = flake.cell * FRAMES + flake.frame;
      if (seen.has(at)) {continue;}
      seen.add(at);
      const col = (at % state.layout.cols) * CELL;
      const row = Math.floor(at / state.layout.cols) * CELL;
      state.atlasCtx.clearRect(col, row, CELL, CELL);
      const cell = state.palette[flake.cell];
      drawCell(state.atlasCtx, at, cell ? cell.color : "#87ceeb");
    }
  }

  /**
   * Re-derives every slot's colour from the rotated theme colour and repaints the atlas.
   *
   * @returns {void}
   */
  function applyHue() {
    if (!state.baseRgb) {return;}
    state.rgb = rotateHue(state.baseRgb, state.hue);
    for (const cell of state.palette) {cell.color = cellColor(cell);}
    if (state.atlasCtx) {repaintAllCells();}
    sendCells();
  }

  /**
   * Sets how far individual crystals may sit off the field's hue.
   *
   * @param {number} percent the spread as a percentage of {@link HUE_SPREAD_MAX};
   *   anything outside 0-100 is ignored
   * @returns {void}
   */
  function setVariation(percent) {
    const want = Number(percent);
    if (!Number.isFinite(want)) {return;}
    state.variation = Math.max(0, Math.min(100, want)) / 100;
    applyHue();
  }

  /**
   * Re-deals the field from a new seed and replaces every flake standing on it.
   *
   * This is the panel's reseed button: the reviewer wants a different set of crystals
   * now, not a field that turns over as flakes happen to fall out. The viewport, the
   * palette and the flake count stay as they are and only what the slots hold is new,
   * so the change costs one message instead of a teardown and re-handshake.
   *
   * @param {number} seed seed to draw the new field from; anything else draws a new one
   * @returns {void}
   */
  function reseed(seed) {
    if (!state.specs.length) {return;}
    const want = Number(seed);
    state.rnd = prng(Number.isFinite(want) ? want : (Date.now() & 0x7fffffff));
    const slots = buildSlots(state.base, state.rnd);
    state.shapes = slots.shapes;
    state.wire = slots.wire;
    state.solids = slots.solids;
    state.specs = slots.specs;
    state.seeds = slots.seeds;
    state.palette = buildPalette(state.base, rgbText(state.rgb), state.rnd, state.shapes.length);
    if (state.atlasCtx) {repaintAllCells();}
    // Every flake is replaced, and replaced in place: the field keeps its count and its
    // flakes keep their cells, so a reseed is a new set of crystals, not a new field.
    const live = state.flakes.length;
    state.flakes.length = 0;
    setCount(live);
    state.dirtyAll = true;
    sendCells();
    sendSlots();
  }

  /**
   * Tells the page that the atlas it paints is out of date.
   *
   * When the worker rasterizes it patches its own atlas and the page keeps drawing the
   * same image. In the mode where the browser has no OffscreenCanvas the page owns the
   * atlas, so a hue, a variant or a reseed has to travel back over the channel for the
   * page to repaint it — otherwise the panel's controls would only take effect on the
   * next restart.
   *
   * @returns {void}
   */
  function sendCells() {
    if (state.rasterized || !state.prepared || !state.shapes.length) {return;}
    send({
      type: "cells",
      cells: state.palette,
      shapes: state.shapes,
      layout: state.layout,
      cell: CELL,
      frames: FRAMES,
      count: state.base
    });
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
      for (const flake of state.flakes) {
        const life = flake.life;
        stepFlake(flake, state.bounds, state.rnd);
        // A flake that was just recycled is above the top edge and has not been drawn
        // on this step, which makes this the one moment its crystal can change without
        // the viewer watching a flake re-draw itself in mid-air.
        if (flake.life !== life && state.rnd() < state.refreshChance) {
          refreshCrystal(flake);
        }
      }
      state.accumulator -= FRAME_MS;
      steps++;
    }
    if (steps === MAX_STEPS) {state.accumulator = 0;}
    return steps;
  }

  /**
   * Paints one wireframe flake as vectors, at the orientation its solid has tumbled to.
   *
   * The atlas cannot carry this motion. Sixteen poses step the solid's orientation by
   * 22 degrees at a time, and a solid that is held, jumped and held again reads as a
   * flip book however slowly it is paced. Twelve vertices and forty-two segments cost
   * about as much as one more flake's drawImage, and they make the tumble continuous on
   * all three axes instead.
   *
   * @param {OffscreenCanvasRenderingContext2D} ctx the canvas context, already set to
   *   this flake's position, rotation and size
   * @param {Object} flake the flake to paint, with its solid and tumble attached
   * @returns {void}
   */
  function paintWire(ctx, flake) {
    const radius = SHAPE_VIEW / 2 - 1;
    const groups = wireBands(wireVertices(flake.solid, flake.tiltX, flake.tiltY, radius));
    // The transform maps a two-unit box onto the flake's sprite and the atlas draws its
    // shapes into a cell this many units wide, so the same scale makes a vector solid
    // exactly the size the cell would have been.
    ctx.save();
    ctx.scale(2 / SHAPE_VIEW, 2 / SHAPE_VIEW);
    const cell = state.palette[flake.cell];
    const color = cell ? cell.color : "#87ceeb";
    ctx.fillStyle = color;
    ctx.strokeStyle = color;
    ctx.lineCap = "round";
    // A bright centre, as the flat crystals have, or the whole flake is hairlines
    // crossing an empty middle.
    const core = new Path2D();
    const near = radius * flake.solid.core;
    const far = near * 0.55;
    for (let i = 0; i < 12; i++) {
      const r = i % 2 ? far : near;
      const a = flake.solid.coreRot + i * Math.PI / 6;
      const x = Math.sin(a) * r;
      const y = -Math.cos(a) * r;
      if (i === 0) {core.moveTo(x, y);} else {core.lineTo(x, y);}
    }
    core.closePath();
    ctx.fill(core);
    for (let g = 0; g < groups.length; g++) {
      const segments = groups[g];
      if (segments.length === 0) {continue;}
      const path = new Path2D();
      for (const [p, q] of segments) {
        path.moveTo(p[0], p[1]);
        path.lineTo(q[0], q[1]);
      }
      ctx.lineWidth = WIRE_STROKE_NEAR * WIRE_DEPTH[g];
      ctx.stroke(path);
    }
    ctx.restore();
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
      ctx.setTransform(a, b, buf[at + F_C], buf[at + F_D], buf[at + F_X], buf[at + F_Y]);
      ctx.globalAlpha = buf[at + F_ALPHA];
      const flake = state.flakes[i];
      if (flake.solid) {
        paintWire(ctx, flake);
      } else {
        const col = (cell % state.layout.cols) * CELL;
        const row = Math.floor(cell / state.layout.cols) * CELL;
        ctx.drawImage(state.atlas, col, row, CELL, CELL, -1, -1, 2, 2);
      }
      // |a| + |b| is the rotated sprite's half-extent on each axis, so the rect
      // recorded here is exactly the box the next frame has to erase. A wireframe's
      // points stick out past the cell it would have been drawn from, so its box is
      // that much bigger or the previous frame smears at the corners.
      // The two columns of the transform, not one: a figure habit squashes its x axis,
      // so the y extent comes from the other column and a rect built from the first
      // alone leaves a strip of the previous frame behind.
      const half = Math.max(Math.abs(a) + Math.abs(b), Math.abs(buf[at + F_C]) + Math.abs(buf[at + F_D])) *
                   (flake.solid ? 1.12 : 1);
      const rt = i * 4;
      // Rounded out, not in. The sprite's edge is antialiased, so its coverage runs a
      // fraction of a pixel past the box the transform describes, and a clear of exactly
      // that box leaves a faint fringe one pixel deep along the way the flake came from —
      // which is what a trail is.
      rects[rt] = Math.floor(buf[at + F_X] - half) - 1;
      rects[rt + 1] = Math.floor(buf[at + F_Y] - half) - 1;
      rects[rt + 2] = Math.ceil(half * 2) + 2;
      rects[rt + 3] = Math.ceil(half * 2) + 2;
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
    send({
      type: "stats",
      fps: elapsed > 0 ? (state.frames * 1000) / elapsed : 0,
      count: state.count,
      refreshes: state.refreshes,
      // The panel's thumbnails are crystals of its own unless they are painted from the
      // seeds the field's slots were built from, so the current ones ride along here:
      // a rolling refresh changes a slot's crystal with no message of its own.
      slots: slotSeeds()
    });
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
    state.solids = slots.solids;
    state.specs = slots.specs;
    state.seeds = slots.seeds;
    state.baseRgb = parseHex(msg.baseColor) || [135, 206, 235];
    state.rgb = rotateHue(state.baseRgb, state.hue);
    state.palette = buildPalette(state.base, rgbText(state.rgb), state.rnd, state.shapes.length);
    // Sized for the largest field the panel can ask for, not for the slots this viewport
    // was dealt: a frame records one rect per flake so the next frame can erase it, and a
    // buffer that runs out mid-frame drops the writes for the rest of it. Those flakes are
    // then never cleared, and every frame after paints on top of the last — a field of
    // flakes smearing into streaks the moment the count passes the slot count.
    state.dirtyRects = new Float32Array(Math.max(state.base, TUNING_MAX_FLAKES) * 4);
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
      shapes: state.shapes,
      slots: slotSeeds()
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
        const steps = simulate(now);
        const draw = paintBeat(state.beat, state.rate);
        state.beat++;
        // Skipping the pack as well as the send is the point: on a throttled beat
        // nothing is copied, nothing is transferred, and the page paints nothing.
        if (draw) {
          // On the beat that gets drawn, so a colour cycle moves the atlas it is about
          // to sample from and not one it has already shown.
          advanceCycle(steps);
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
      case "quality": {
        // Not `Number(msg.count) || state.count`: zero is a count, and the panel's
        // slider runs down to it.
        const asked = Number(msg.count);
        setCount(Number.isFinite(asked) ? asked : state.count);
        state.dirtyAll = true;
        break;
      }
      case "rate":
        setRate(Number(msg.rate));
        break;
      case "habits":
        setHabits(msg.enabled);
        break;
      case "refresh":
        setRefreshChance(msg.chance);
        break;
      case "hue":
        setHue(msg.degrees);
        break;
      case "variation":
        setVariation(msg.percent);
        break;
      case "cycle":
        setCycle(msg.on);
        break;
      case "motion":
        setMotion(msg);
        break;
      case "size":
        setSize(msg);
        break;
      case "reseed":
        reseed(msg.seed);
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

  // Only where this is not a page. The console also loads this file as a page script,
  // for the panel's thumbnails, and a page has a window.onmessage that other scripts may
  // already own — and every message posted to the window, iframe resizes included, would
  // land in the snow's handler. A worker, and the test harness that drives one, have no
  // document.
  if (typeof self.document === "undefined") {self.onmessage = onMessage;}

  // The JS harness (tools/test/js/pure/snowman.test.js) loads this file in a
  // worker-like context and drives these directly, so the physics is pinned
  // without a browser.
  self.SNOWMAN = Object.freeze({
    FRAME_MS, STRIDE, CELL, MIN_FLAKES, MAX_FLAKES, TUNING_MAX_FLAKES, DEATH_SPIN_STEPS, DEATH_SPIN_GROWTH,
    REMOVAL_SIZE,
    DEATH_SPIN_MAX, DEATH_SPIN_BIAS, DEATH_SPIN_REVERSE, DEATH_WOBBLE_MIN, DEATH_WOBBLE_MAX,
    DEATH_DRIFT_DECAY, SPIN_LATERAL, DEATH_FALL_ACCEL, SOFT_REACH, SEPARATION, GUST_DECAY,
    GUST_MAX, GRAZE_SPIN, WARP_CHANCE, WARP_MAX, SPIRAL_CHANCE, CRYSTAL_REFRESH_CHANCE,
    CYCLE_DEGREES_PER_SEC,
    FRAMES, FRAME_BEATS,
    WIRE_STROKE_NEAR, WIRE_DEPTH, WIRE_CIRC, WIRE_SPIN_MIN, WIRE_SPIN_MAX,
    SPIN_VAR_MAX,
    WIRE_TUMBLE_MIN, WIRE_TUMBLE_MAX, tumbleRate,
    SLOW_FALL_SPEED, SLOW_SPIN_RAMP,
    WIRE_DEATH_SPIN_MAX, MIN_SPIN, HABITS, STEM_SHARE, ICOSA, WIRE,
    SHAPE_VIEW, RATE_MAX,
    BASE_SIZE, MIN_SIZE, SIZE_MIN, SIZE_MAX, DEFAULT_SIZE, DEFAULT_SIZE_VAR, FIGURE_SIZE,
    flakeSize, removalSize,
    // The protocol's entry point, for a harness that drives the worker from a page
    // (a page has a document, so this module does not claim window.onmessage there).
    handle: data => onMessage({data}),
    flakeCountFor, atlasLayout, prng, parseHex, varyColor, varyDelta, colorText, rgbText,
    rotateHue, hueOf, buildPalette, DEFAULT_VARIATION, DEFAULT_GRAVITY_VAR, DEFAULT_SPIN_VAR,
    coord, habitSpec, fieldSpecs, setHue, setVariation, HUE_SPREAD_MAX,
    paintWire,
    spike, plate, hexPath, ngonPath, hollowPlate, dot, bar, band, star, stellate, rotAxis,
    armDendrite, armFern, armNeedle, armSectored, armCapped, armBeaded, armRinged, armStar,
    armHexPlate, armTwin, armDodeca,
    buildShape, buildShapes, buildSlots, buildSlotFrames, dealSlots, refreshSpec, slotSeeds, dealSlot,
    refreshCrystal, shapePaths, spiralVane, activeHabits, setHabits, setRefreshChance,
    setCycle, reseed, anyOn, cellColor,
    wireOrientation, wireVertices, wireBands, depthBucket,
    pickHabit, seedDeathSpin, deathSpinAt, separateFlakes,
    createFlake, respawn, spinSpeed, stepFlake, writeFlake, paintBeat
  });
})();
