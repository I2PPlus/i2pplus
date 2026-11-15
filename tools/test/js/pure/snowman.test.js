/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/snowflakes/snowman.js —
 * the dedicated worker that runs the console's festive snow.
 *
 * The worker exists so the animation costs the page nothing it cannot spare;
 * these pin the parts that make that true, and the physics invariants that keep
 * the effect looking like snow while it happens.
 *
 *   - the handshake: init answers ready with the atlas table, and no frame is
 *     produced until the page answers with the rasterized atlas;
 *   - the frame payload is one packed draw transform per flake, scale and
 *     rotation already baked in and opacity clamped, because the page is not
 *     supposed to do any trigonometry per frame;
 *   - the simulation is a fixed timestep, so 30 ticks at 33.3ms and 60 ticks at
 *     16.7ms produce the same flake field — the original per-frame version fell
 *     at whatever rate the display happened to refresh at;
 *   - a stalled tab drops its backlog rather than lurching;
 *   - flakes wrap horizontally, spin, fall out and recycle at the top;
 *   - a resize retargets each flake's travel budget, which the original captured
 *     once at construction and never updated;
 *   - the count the quality controller asks for is the count the next frame holds;
 *   - the flake shapes are generated here rather than fetched: one arm of filled
 *     spikes replicated six times, so the six-fold symmetry is structural, every
 *     slot gets its own crystal, and the markup stays smaller than the files it
 *     replaces.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const SRC = readFileSync(
  new URL("../../../../apps/routerconsole/jsp/js/snowflakes/snowman.js", import.meta.url), "utf8");

/** One 60Hz frame, matching the worker's own fixed step. */
const FRAME_MS = 1000 / 60;

/**
 * Loads the worker in a context that imitates a dedicated worker's global scope:
 * self is the global, postMessage collects what it answers with, and no
 * OffscreenCanvas is present, which is the mode where the worker only does
 * physics and the page paints.
 *
 * @param {Object} [globals] extra globals to install before the script runs
 * @returns {{send: function(Object): void, messages: function(): Object[],
 *   take: function(string): Object|undefined, api: function(): Object}} drive
 *   the protocol, list what it posted, take one message by type, and reach the
 *   pure helpers the worker exports
 */
function openWorker(globals = {}) {
  const posted = [];
  const ctx = {
    postMessage: message => posted.push(message),
    setTimeout: () => 0,
    clearTimeout: () => {},
    performance: {now: () => 0}
  };
  Object.assign(ctx, globals);
  ctx.self = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);
  return {
    send: message => ctx.onmessage({data: message}),
    messages: () => posted.splice(0, posted.length),
    take: type => {
      const at = posted.findIndex(message => message && message.type === type);
      return at === -1 ? undefined : posted.splice(at, 1)[0];
    },
    api: () => ctx.SNOWMAN
  };
}

/**
 * The worker's pure helpers, from a throwaway context.
 *
 * @returns {Object} the exported physics surface
 */
function api() {
  return openWorker().api();
}

/**
 * Sends init and, unless told to stop there, the atlas that releases the worker
 * into producing frames.
 *
 * @param {Object} [options] viewport, pixel ratio and seed to initialize with
 * @param {boolean} [options.atlas=true] whether to complete the handshake
 * @returns {{w: Object, ready: Object}} the driven worker and its ready message
 */
function openPrepared(options = {}) {
  const worker = openWorker();
  worker.send({
    type: "init",
    width: options.width || 800,
    height: options.height || 600,
    dpr: options.dpr || 1,
    baseColor: options.baseColor || "#78c3f2",
    seed: options.seed === undefined ? 12345 : options.seed
  });
  const ready = worker.take("ready");
  if (!ready) {throw new Error("worker did not answer init with ready");}
  if (options.atlas !== false) {worker.send({type: "atlas"});}
  // One clock per worker, advanced by the caller, so a test cannot accidentally
  // hand the worker a timestamp older than the one it last simulated to. The
  // timeline is multiplied out rather than summed, because a summed one drifts
  // and a drifting timeline makes a step-count comparison meaningless.
  let t = 0;
  return {
    w: worker,
    ready,
    at: () => t,
    ticks: (count, step = FRAME_MS) => {
      const base = t;
      let last;
      for (let i = 1; i <= count; i++) {
        t = base + i * step;
        worker.send({type: "tick", t});
        last = worker.take("frame") || last;
      }
      return last;
    }
  };
}

test("flake count scales with the viewport and stays inside its bounds", () => {
  const helpers = api();
  assert.equal(helpers.flakeCountFor(0, 0), helpers.MIN_FLAKES);
  assert.equal(helpers.flakeCountFor(200, 200), helpers.MIN_FLAKES, "a small window keeps the floor");
  assert.equal(helpers.flakeCountFor(1280, 720), 12);
  assert.equal(helpers.flakeCountFor(1366, 768), 13);
  assert.equal(helpers.flakeCountFor(1920, 1080), helpers.MAX_FLAKES);
  assert.equal(helpers.flakeCountFor(4096, 2160), helpers.MAX_FLAKES);
  assert.equal(helpers.flakeCountFor(7680, 4320), helpers.MAX_FLAKES);
  const mid = helpers.flakeCountFor(1280, 720);
  assert.ok(mid > helpers.MIN_FLAKES && mid < helpers.MAX_FLAKES,
            "a desktop window sits between the bounds");
});

test("the atlas grid holds every cell and is sized in whole cells", () => {
  const helpers = api();
  for (const count of [1, 6, 16, 22]) {
    const layout = helpers.atlasLayout(count);
    assert.ok(layout.cols * layout.rows >= count, `${count} cells fit`);
    assert.equal(layout.width, layout.cols * helpers.CELL);
    assert.equal(layout.height, layout.rows * helpers.CELL);
  }
});

test("init answers with the atlas table, and no frame arrives before the atlas", () => {
  const helpers = api();
  const {w, ready} = openPrepared({atlas: false});
  assert.equal(ready.offscreen, false, "no OffscreenCanvas in this context");
  assert.equal(ready.cell, helpers.CELL);
  assert.equal(ready.stride, helpers.STRIDE);
  assert.equal(ready.cells.length, ready.count);
  assert.equal(ready.frames, helpers.FRAMES);
  assert.equal(ready.shapes.length, ready.count * ready.frames, "one shape per frame of every slot");
  assert.ok(ready.layout.cols * ready.layout.rows >= ready.count * ready.frames,
            "the grid holds every frame of every slot");
  for (const cell of ready.cells) {
    assert.ok(cell.type >= 1 && cell.type <= ready.shapes.length,
              "flake type indexes a shape that was actually sent");
    assert.match(cell.color, /^rgb\(\d{1,3},\d{1,3},\d{1,3}\)$/, "a colour the SVG placeholder takes");
  }
  w.send({type: "tick", t: FRAME_MS});
  assert.deepEqual(w.messages(), [], "a worker holding no atlas paints nothing");
});

test("a generated flake is one arm replicated six times, filled, and placeholder-coloured", () => {
  const helpers = api();
  for (let seed = 0; seed < 40; seed++) {
    const svg = helpers.buildShape(helpers.prng(seed * 7919 + 13), helpers.SHAPE_VIEW);
    const wire = svg.includes('stroke-width=');
    assert.match(svg, /^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg"/, "a standalone SVG document");
    assert.ok(svg.endsWith("</svg>"), "and it is closed");
    assert.ok(!svg.includes("currentColor"), "currentColor resolves to black through a blob <img>");
    assert.ok(!svg.includes("fill-rule"),
              "no fill-rule attribute: the hollow plates get their hole from a reversed " +
              "inner ring, which both the SVG fill and the worker's rasterizer honour without one");
    assert.ok(!/NaN|Infinity|undefined/.test(svg),
              "arithmetic that leaked a bad coordinate into the markup");
    const uses = svg.match(/<use /g) || [];
    // A flat crystal is one arm replicated about the centre: six times for every habit
    // but the twelve-branched twin, which is two six-fold crystals grown through each
    // other, and once for the wireframe solid, which is not six-fold at all.
    assert.ok(wire ? uses.length === 1 : uses.length === 6 || uses.length === 12,
              `${uses.length} copies: six-fold, the twelve-fold twin, or one for a wireframe`);
    if (!wire) {
      const step = 360 / uses.length;
      for (let k = 1; k < uses.length; k++) {
        assert.ok(svg.includes(`rotate(${step * k})`), `and copy ${k} is turned through ${step * k} degrees`);
      }
    }
    assert.equal((svg.match(/<defs>/g) || []).length, 1, "the arm itself is defined exactly once");
    const box = svg.match(/viewBox="([^"]+)"/)[1].split(" ").map(Number);
    assert.equal(box.length, 4);
    assert.equal(box[2], box[3], "a square viewBox, so a flake is never drawn as an ellipse");
    assert.equal(box[0] + box[2] / 2, 0, "and centred on the origin the rotations turn about");
    assert.ok(!/<(path|polygon|rect)[^>]*\bstroke=(?!":color:")/.test(svg),
              "filled spikes, not strokes: a stroke is a hairline at the 11-16px a flake shows at");
    if (wire) {
      const widths = (svg.match(/stroke-width="([-\d.]+)"/g) || [])
                     .map(m => Number(/"([-\d.]+)"/.exec(m)[1]));
      assert.ok(widths.length > 1, "a wireframe is drawn in depth bands, not one flat stroke");
      assert.ok(Math.max(...widths) >= 2,
                `the nearest edges at ${Math.max(...widths)} shape units are visible at display size`);
      assert.ok(Math.max(...widths) <= helpers.WIRE_STROKE_NEAR + 0.05,
                "nothing is drawn thicker than the nearest band");
      // The far bands are under a pixel at display size on purpose: that is the depth
      // cue, and it is what keeps the back half of the solid out of the middle of a
      // sixteen-pixel sprite without a cut that makes lines pop in and out.
      assert.ok(Math.min(...widths) <= helpers.WIRE_STROKE_NEAR * 0.15,
                `the farthest band is ${Math.min(...widths)} units, and should be near invisible`);
    }
  }
});

test("the colour placeholder sits where a use clone will actually inherit it", () => {
  const helpers = api();
  const svg = helpers.buildShape(helpers.prng(31), helpers.SHAPE_VIEW);
  // a use element deep-clones its target, and the clone inherits from the use, not
  // from the target's original parent — so a fill on a wrapper around the defs
  // group, or on the svg element, colours the markup and renders black
  assert.match(svg, /<defs><g id="a" fill=":color:">/,
               "the placeholder is on the referenced group itself");
  assert.equal((svg.match(/:color:/g) || []).length, 1, "and one substitution colours the whole flake");
});

test("generated shapes are seeded, distinct per flake, and small", () => {
  const helpers = api();
  const count = helpers.MAX_FLAKES;
  const a = helpers.buildShapes(count, helpers.prng(20261201));
  const b = helpers.buildShapes(count, helpers.prng(20261201));
  assert.equal(a.length, count * helpers.FRAMES, "one shape per frame of every slot");
  assert.deepEqual(a, b, "the same seed draws the same crystals");
  // A slot's frames are the same crystal at successive points of its warp cycle, so
  // they must be built from one seed: same arm, different twist. A flake with no
  // warp has nothing to animate, and its whole strip is one drawing.
  let animated = 0;
  for (let i = 0; i < count; i++) {
    const strip = a.slice(i * helpers.FRAMES, (i + 1) * helpers.FRAMES);
    const distinct = new Set(strip).size;
    // A warped strip can land on the same twist twice in its cycle — the warp walks
    // a sine, and two of its samples can agree — so the invariant is "one drawing,
    // or most of a cycle of them", not "exactly one per frame".
    assert.ok(distinct === 1 || distinct >= helpers.FRAMES / 2,
              `slot ${i} frames are one drawing or most of a cycle, not ${distinct}`);
    if (distinct > 1) {animated++;}
  }
  assert.ok(animated >= count / 2, `${animated} of ${count} slots animate through their frames`);
  for (const svg of a) {
    // the six hand-drawn files this replaces average 2.5 KB and top out at 3.7 KB;
    // a generated flake is smaller than that and costs no request at all
    assert.ok(svg.length < 3000, `markup is ${svg.length} bytes, posted once per page load`);
  }
});

test("the palette names a shape the worker actually sent", () => {
  const helpers = api();
  const color = "#87ceeb";
  const max = helpers.MAX_FLAKES;
  const generated = helpers.buildPalette(max, color, helpers.prng(5), max);
  assert.deepEqual(generated.map(c => c.type), generated.map((_, i) => i + 1),
                   "with one shape per slot, each flake draws its own");
  const fallback = helpers.buildPalette(max, color, helpers.prng(5), 6);
  for (const cell of fallback) {
    assert.ok(cell.type >= 1 && cell.type <= 6, "a shared pool of six is indexed inside itself");
  }
  assert.notEqual(new Set(fallback.map(c => c.type)).size, 1, "and spread over the field, not cycled");
});

test("the same seed produces the same flake field", () => {
  const a = openPrepared({seed: 4242}).ready;
  const b = openPrepared({seed: 4242}).ready;
  const c = openPrepared({seed: 99}).ready;
  assert.equal(JSON.stringify(a.cells), JSON.stringify(b.cells), "a seeded run is reproducible");
  assert.notEqual(JSON.stringify(a.cells), JSON.stringify(c.cells), "and the seed is actually used");
});

test("a tick answers with one packed draw transform per flake", () => {
  const run = openPrepared({seed: 7});
  const frame = run.ticks(3);
  assert.ok(frame, "frames follow ticks");
  assert.equal(frame.count, run.ready.count);
  assert.equal(frame.buf.length, run.ready.count * run.ready.stride);
  for (let i = 0; i < frame.count; i++) {
    const at = i * run.ready.stride;
    const a = frame.buf[at + 2];
    const b = frame.buf[at + 3];
    for (const value of [frame.buf[at], frame.buf[at + 1], a, b, frame.buf[at + 4], frame.buf[at + 5]]) {
      assert.ok(Number.isFinite(value), `flake ${i} carries a finite transform`);
    }
    assert.ok(frame.buf[at + 6] >= 0 && frame.buf[at + 6] <= 1, "opacity is clamped");
    assert.ok(frame.buf[at + 7] >= 0 && frame.buf[at + 7] < run.ready.count * run.ready.frames,
              "the atlas cell exists");
    // A rotation times a uniform scale: a == d and c == -b, because the page
    // draws a unit square through this matrix. A non-uniform pair is a bug.
    assert.equal(frame.buf[at + 4], -b);
    assert.equal(frame.buf[at + 5], a);
    assert.ok(Math.hypot(a, b) > 0, "a flake is never scaled to nothing");
  }
});

test("the simulation is a fixed step: 30Hz and 60Hz agree", () => {
  const slow = openPrepared({seed: 31337});
  const fast = openPrepared({seed: 31337});
  const slowFrame = slow.ticks(30, FRAME_MS * 2);
  const fastFrame = fast.ticks(60);
  assert.ok(slowFrame && fastFrame);
  assert.equal(slowFrame.buf.length, fastFrame.buf.length);
  for (let i = 0; i < slowFrame.buf.length; i++) {
    assert.ok(Math.abs(slowFrame.buf[i] - fastFrame.buf[i]) < 1e-4,
              `slot ${i} agrees across refresh rates: ${slowFrame.buf[i]} vs ${fastFrame.buf[i]}`);
  }
});

test("a stalled tab drops its backlog instead of lurching", () => {
  const run = openPrepared({seed: 5});
  const before = run.ticks(10);
  run.w.send({type: "tick", t: run.at() + 3600000});
  const after = run.w.take("frame");
  assert.ok(after);
  let moved = 0;
  for (let i = 0; i < run.ready.count; i++) {
    moved += Math.abs(after.buf[i * 8 + 1] - before.buf[i * 8 + 1]);
  }
  // Four steps of fall is the ceiling. An unclamped accumulator would have
  // simulated the whole hour the tab was hidden, in one frame.
  assert.ok(moved < 4 * 60, `an hour hidden costs a few steps, not the hour: moved ${moved}`);
});

test("every flake falls, and the field stays within one sprite of the viewport", () => {
  const run = openPrepared({seed: 11, height: 600});
  const first = run.ticks(1);
  const later = run.ticks(20);
  for (let i = 0; i < run.ready.count; i++) {
    const before = first.buf[i * 8 + 1];
    const after = later.buf[i * 8 + 1];
    assert.ok(after > before, `flake ${i} fell: ${before} to ${after}`);
    assert.ok(after <= 600 + 64, `flake ${i} stays near the viewport`);
  }
});

test("a flake pushed past the side edge reappears on the other side", () => {
  const helpers = api();
  const rnd = helpers.prng(1);
  const bounds = {width: 400, height: 300};

  const right = helpers.createFlake(bounds, 0, rnd);
  right.x = bounds.width + right.size + 5;
  right.windPhase = Math.PI / 2;
  right.windSpeed = 1;
  helpers.stepFlake(right, bounds, rnd);
  assert.equal(right.x, -right.size, "wrapped to the left edge");

  const left = helpers.createFlake(bounds, 0, rnd);
  left.x = -left.size - 5;
  left.windPhase = -Math.PI / 2;
  left.windSpeed = 1;
  helpers.stepFlake(left, bounds, rnd);
  assert.equal(left.x, bounds.width + left.size, "wrapped to the right edge");
});

test("a flake that shrinks past the threshold spins, falls out, and recycles", () => {
  const helpers = api();
  const rnd = helpers.prng(3);
  const bounds = {width: 400, height: 300};
  const flake = helpers.createFlake(bounds, 0, rnd);
  flake.totalDistance = flake.maxDistance - 1;
  helpers.stepFlake(flake, bounds, rnd);
  assert.ok(flake.size < 9, `a spent flake is below the removal size: ${flake.size}`);
  assert.equal(flake.isDying, true, "the shrink starts the death spin");
  assert.ok(Math.abs(flake.deathSpinSpeed) >= 0.01, "seeded from the rate it was already turning at");
  assert.equal(Math.sign(flake.deathSpinSpeed), Math.sign(flake.rotationSpeed),
               "and in the direction it was already turning");
  assert.equal(flake.vy, flake.speed, "it still drifts at its fall speed during the spin");

  const spun = flake.deathSpinSpeed;
  for (let i = 0; i < helpers.DEATH_SPIN_STEPS + 4; i++) {helpers.stepFlake(flake, bounds, rnd);}
  assert.ok(flake.vy > flake.speed, "past the spin delay it accelerates downward");
  // The envelope grows until the cap stops it. A flake whose living spin was already
  // ramped up by the slow-flake spin-up starts near that cap, so the honest statement
  // is that it reaches it, not that it triples whatever it started at.
  assert.ok(Math.abs(flake.spinEnv) >= Math.min(helpers.DEATH_SPIN_MAX, Math.abs(spun) * 3),
            "and the spin envelope grows as it falls out");
  assert.ok(Math.abs(flake.spinEnv) <= helpers.DEATH_SPIN_MAX,
            "and the envelope is capped, so the turn stays resolvable");

  for (let i = 0; i < 400 && flake.isDying; i++) {helpers.stepFlake(flake, bounds, rnd);}
  assert.equal(flake.isDying, false, "it recycles at the top");
  assert.equal(flake.size, flake.initialSize, "recycled whole");
  assert.ok(flake.y <= 0, "recycled above the viewport");
});

test("a fall-out spins inside the rate a six-fold sprite can be sampled at, and turns around", () => {
  const helpers = api();
  const bounds = {width: 400, height: 900};
  // A six-fold flake looks the same every 2pi/6 radians, so one sample a step can
  // only follow it up to half that. Past this the sprite aliases and the turn reads
  // as a shimmer rather than as a turn, which is what the uncapped model did.
  const limit = Math.PI / 6;
  let reversals = 0;
  let measured = 0;
  for (let seed = 0; seed < 12; seed++) {
    const rnd = helpers.prng(seed * 613 + 5);
    const flake = helpers.createFlake(bounds, 0, rnd);
    flake.totalDistance = flake.maxDistance;
    helpers.stepFlake(flake, bounds, rnd);
    let direction = Math.sign(flake.deathSpinSpeed);
    for (let i = 0; i < 80 && flake.isDying; i++) {
      const before = flake.rotation;
      helpers.stepFlake(flake, bounds, rnd);
      if (!flake.isDying) {break;}
      const rate = flake.rotation - before;
      measured++;
      assert.ok(Math.abs(rate) <= limit * 1.02,
                `step ${i} turns ${rate.toFixed(3)} rad, past the ${limit.toFixed(3)} sampling limit`);
      const now = Math.sign(flake.deathSpinSpeed);
      if (now !== 0 && now !== direction) {reversals++; direction = now;}
    }
  }
  assert.ok(measured > 300, `${measured} dying steps measured`);
  assert.ok(reversals >= 12, `the wobble stalls and reverses flakes (${reversals} across 12 fall-outs)`);
});

test("a fall-out keeps the sideways rate the flake had when it let go", () => {
  const helpers = api();
  const rnd = helpers.prng(11);
  const bounds = {width: 400, height: 900};
  const flake = helpers.createFlake(bounds, 0, rnd);
  flake.totalDistance = flake.maxDistance;
  flake.windPhase = Math.PI / 2;
  flake.windSpeed = 0.8;
  helpers.stepFlake(flake, bounds, rnd);
  assert.ok(Math.abs(flake.driftVx) > 0.5,
            `the sway's rate is carried into the fall-out: ${flake.driftVx.toFixed(2)} px a step`);

  let across = 0;
  let down = 0;
  let px = flake.x;
  let py = flake.y;
  for (let i = 0; i < 24 && flake.isDying; i++) {
    helpers.stepFlake(flake, bounds, rnd);
    across += Math.abs(flake.x - px);
    down += Math.abs(flake.y - py);
    px = flake.x;
    py = flake.y;
  }
  // The old model stopped moving sideways the step the flake started dying, and
  // measured 2-18% as wide as long: a dead drop. An arc that leaves on the heading
  // the flake was already on is what reads as something falling out of the sky.
  assert.ok(across / down > 0.15,
            `the fall-out traces ${(100 * across / down).toFixed(0)}% as wide as it is long`);
});

test("a flake about to be overtaken is nudged aside, and one falling in step is not", () => {
  const helpers = api();
  const rnd = helpers.prng(4);
  const bounds = {width: 400, height: 300};
  const lower = helpers.createFlake(bounds, 0, rnd);
  const upper = helpers.createFlake(bounds, 1, rnd);
  for (const [flake, size, x, y] of [[lower, 16, 100, 100], [upper, 11, 100, 80]]) {
    flake.size = size;
    flake.initialSize = size;
    flake.x = x;
    flake.y = y;
    flake.vx = 0;
  }
  lower.vy = 0.4;
  upper.vy = 1.2;

  helpers.separateFlakes([lower, upper]);
  assert.ok(lower.gustVy > 0, "the flake below is pushed out of the way");
  assert.ok(upper.gustVy < 0, "and the one catching it is slowed");
  assert.ok(Math.abs(lower.gustVy) < 0.01, `the push is a nudge, not a shove: ${lower.gustVy}`);

  for (const flake of [lower, upper]) {flake.gustVx = 0; flake.gustVy = 0; flake.gustSpin = 0;}
  upper.vy = lower.vy;
  helpers.separateFlakes([lower, upper]);
  assert.equal(lower.gustVy, 0, "two flakes falling in step do not notice each other");
  assert.equal(upper.gustVx, 0, "so the field does not jostle continuously");
});

test("no shipped shape is a bare hexagon", () => {
  const helpers = api();
  const shapes = helpers.buildShapes(helpers.MAX_FLAKES, helpers.prng(20261114));
  for (const svg of shapes) {
    for (const path of svg.match(/<path d="[^"]+"\/>/g) || []) {
      // a wireframe's strokes are open line segments, not a polygon outline
      if (path.includes('fill="none"')) {continue;}
      const d = /d="([^"]+)"/.exec(path)[1];
      if (!d.endsWith("Z")) {continue;}
      const vertices = (d.match(/L/g) || []).length + 1;
      assert.notEqual(vertices, 6, `a six-sided polygon reads as a primitive, not a flake: ${path}`);
    }
  }
});

test("the field shows every habit, not whatever the weighted draws happened to land on", () => {
  const helpers = api();
  const slots = helpers.buildSlots(helpers.MAX_FLAKES, helpers.prng(20261114));
  assert.equal(slots.wire.length, helpers.MAX_FLAKES, "one entry per slot");
  assert.ok(slots.wire.includes(true) && slots.wire.includes(false),
            "a dealt deck shows both the wireframe habit and the flat ones");
  // The deck deals one of each habit before any habit is drawn twice, so the count
  // of distinct shape strips is at least the number of habits whatever the seed.
  const animated = [];
  for (let seed = 0; seed < 8; seed++) {
    const dealt = helpers.buildSlots(helpers.MIN_FLAKES, helpers.prng(seed * 104729 + 7));
    const field = dealt.shapes;
    assert.equal(field.length, helpers.MIN_FLAKES * helpers.FRAMES, `seed ${seed} fills every slot`);
    const strips = [];
    for (let i = 0; i < helpers.MIN_FLAKES; i++) {
      strips.push(new Set(field.slice(i * helpers.FRAMES, (i + 1) * helpers.FRAMES)).size);
    }
    // A strip is either one static pose or a whole warp cycle. The floor is a quarter
    // of the cycle, not half: a habit that takes a gentle warp (see the sixth column
    // of HABITS) displaces its points by less than coord() rounds, so some of its
    // sixteen frames come out identical and a legitimately animated strip can hold as
    // few as four distinct poses.
    assert.ok(strips.every(n => n === 1 || n >= helpers.FRAMES / 4),
              `seed ${seed} strips are whole cycles, not part-drawn`);
    // A strip animates when its crystal is warped (its frames walk the warp cycle) or
    // when it is a solid (painted as vectors at its own orientation, so its frames are
    // one pose but the flake still turns). Which slots are warped is a draw, so the
    // check is on the field as a whole, not on one seed.
    animated.push(strips.filter((n, i) => n > 1 || dealt.wire[i]).length);
  }
  const mean = animated.reduce((sum, n) => sum + n, 0) / animated.length;
  assert.ok(mean >= helpers.MIN_FLAKES * 0.5,
            `${mean.toFixed(1)} of ${helpers.MIN_FLAKES} slots animate through their frames`);
  assert.ok(Math.min(...animated) >= helpers.MIN_FLAKES / 3,
            `the worst seed still animates ${Math.min(...animated)} slots`);
});

test("a wireframe tumbles continuously on all three axes, at a relaxed pace", () => {
  const helpers = api();
  const bounds = {width: 1280, height: 800};
  const perSecond = rate => Math.abs(rate) * 1000 / helpers.FRAME_MS * 180 / Math.PI;
  for (let seed = 0; seed < 30; seed++) {
    const rnd = helpers.prng(seed * 7717 + 3);
    const wire = helpers.createFlake(bounds, 0, rnd, true,
                                     helpers.wireOrientation(helpers.prng(seed * 31337 + 5)));
    const flat = helpers.createFlake(bounds, 0, helpers.prng(seed * 7717 + 3), false);
    // Relaxed: a solid turning at a flat crystal's rate reads as a drill, and its
    // twelve-pointed silhouette aliases at half the rate a six-fold one does.
    const z = perSecond(helpers.spinSpeed(wire));
    assert.ok(z >= 20 && z <= 45, `the z axis turns at ${z.toFixed(0)} degrees a second`);
    assert.ok(z <= perSecond(helpers.spinSpeed(flat)),
              "and slower than a flat crystal drawn from the same numbers");
    // Both screen axes have a floor on the magnitude, not a range around zero: a
    // wireframe that draws a near-zero rate on one axis falls turning on two, which is
    // the flat spin the solid was built in three dimensions to avoid.
    const floor = perSecond(helpers.WIRE_TUMBLE_MIN);
    const ceiling = perSecond(helpers.WIRE_TUMBLE_MAX);
    for (const axis of ["spinX", "spinY"]) {
      const rate = perSecond(wire[axis]);
      assert.ok(rate >= floor && rate <= ceiling,
                `${axis} tumbles at ${rate.toFixed(0)} degrees a second, outside ${floor.toFixed(0)}-${ceiling.toFixed(0)}`);
    }
    // Continuous: every step advances all three axes by the same small amount, so
    // nothing is held, jumped and held again — the stop-animate-start of a flip book.
    const was = [wire.tiltX, wire.tiltY, wire.rotation];
    const rates = [wire.spinX, wire.spinY, helpers.spinSpeed(wire) + wire.gustSpin];
    helpers.stepFlake(wire, bounds, rnd);
    const moved = [wire.tiltX - was[0], wire.tiltY - was[1], wire.rotation - was[2]];
    for (let i = 0; i < 3; i++) {
      assert.ok(Math.abs(moved[i] - rates[i]) < 1e-9, `axis ${i} advances by its own rate every step`);
      assert.ok(Math.abs(moved[i]) < 0.05, `axis ${i} moves in a step, not a jump`);
    }
  }
  // the atlas holds one pose per wireframe slot, because the motion is not in it
  const slots = helpers.buildSlots(helpers.MAX_FLAKES, helpers.prng(20261114));
  const at = slots.wire.indexOf(true);
  assert.ok(at >= 0, "a dealt deck shows the wireframe habit");
  const strip = slots.shapes.slice(at * helpers.FRAMES, (at + 1) * helpers.FRAMES);
  assert.equal(new Set(strip).size, 1, "a wireframe slot's frames are one pose, not a flip book");
  assert.ok(slots.solids[at], "and the slot carries the solid its flakes are painted from");
  assert.ok(slots.solids.every((s, i) => (s !== null) === slots.wire[i]),
            "exactly one solid per wireframe slot");
});

test("a slow flake spins up as it falls, and a fast one keeps its own rate", () => {
  const helpers = api();
  const bounds = {width: 400, height: 900};
  let slow = 0;
  for (let seed = 0; seed < 40; seed++) {
    const flake = helpers.createFlake(bounds, 0, helpers.prng(seed * 4463 + 17));
    const top = Math.abs(helpers.spinSpeed(flake));
    flake.totalDistance = flake.maxDistance;
    const bottom = Math.abs(helpers.spinSpeed(flake));
    // The ramp applies to every flake under the fall-speed threshold, but it is only
    // visible above the MIN_SPIN floor when the flake's own rate already clears it:
    // one that drew a near-zero rate is pinned to the floor at both ends.
    const slowFlake = flake.speed <= helpers.SLOW_FALL_SPEED;
    if (slowFlake) {
      slow++;
      if (Math.abs(flake.rotationSpeed) > helpers.MIN_SPIN) {
        assert.ok(bottom > top * 1.5,
                  `a flake falling ${flake.speed.toFixed(2)} px a step turns ${top.toFixed(4)} at the top and ${bottom.toFixed(4)} at the bottom`);
      } else {
        assert.ok(bottom >= top, "the floor never lets a ramped flake spin slower than it started");
      }
    } else {
      assert.equal(bottom, top, `a flake falling ${flake.speed.toFixed(2)} px a step keeps ${top.toFixed(4)}`);
    }
  }
  assert.ok(slow > 4 && slow < 36, `${slow} of 40 flakes fall slowly enough to spin up`);
});

test("the stem habits stay a minority of the field", () => {
  const helpers = api();
  for (const count of [helpers.MIN_FLAKES, helpers.MAX_FLAKES, 40]) {
    for (let seed = 0; seed < 8; seed++) {
      const dealt = helpers.dealSlots(count, helpers.prng(seed * 104729 + 7));
      assert.equal(dealt.length, count, `${count} slots dealt`);
      const cap = Math.max(1, Math.round(count * helpers.STEM_SHARE));
      const stems = dealt.filter(h => h.stem).length;
      const branch = dealt.filter(h => !h.stem && !h.wire).length;
      assert.ok(stems <= cap, `${stems} stem crystals in a field of ${count}, cap ${cap}`);
      assert.ok(stems >= 1, "and the field still shows one");
      assert.ok(branch > stems, `branched crystals outnumber stems, ${branch} against ${stems}`);
    }
  }
});

test("a hollow plate keeps its hole", () => {
  const helpers = api();
  const d = /d="([^"]*)"/.exec(helpers.hollowPlate(0, -20, 8, 4, 6, 0.2))[1];
  const rings = d.split("Z").filter(Boolean);
  assert.equal(rings.length, 2, "an outer ring and an inner one in one path");
  const area = ring => {
    const pts = [...ring.matchAll(/(-?[\d.]+) (-?[\d.]+)/g)].map(m => [Number(m[1]), Number(m[2])]);
    let sum = 0;
    for (let i = 0; i < pts.length; i++) {
      const [x1, y1] = pts[i];
      const [x2, y2] = pts[(i + 1) % pts.length];
      sum += x1 * y2 - x2 * y1;
    }
    return sum;
  };
  // The non-zero fill rule both rasterizers use only leaves the middle empty if the
  // two rings wind opposite ways; wound the same way, the plate is solid and the
  // habit's whole point is gone.
  assert.ok(area(rings[0]) * area(rings[1]) < 0, "the inner ring is wound the other way");
});

test("a needle crystal is drawn smaller, and mostly joins its arms to their neighbours", () => {
  const helpers = api();
  const spec = helpers.habitSpec(helpers.HABITS.find(h => h[0] === "needle"));
  assert.ok(spec.scale <= 0.7, `the needle habit draws at ${spec.scale} of the cell radius`);
  const view = helpers.SHAPE_VIEW;
  const reach = view / 2 - 1;
  const seeds = 24;
  let joined = 0;
  for (let seed = 0; seed < seeds; seed++) {
    const svg = helpers.buildShape(helpers.prng(seed * 6151 + 11), view, spec, 0, 1);
    const paths = (svg.match(/d="([^"]*)"/g) || []).map(m => /d="([^"]*)"/.exec(m)[1]);
    const points = [];
    for (const d of paths) {
      const pairs = /(-?[\d.]+) (-?[\d.]+)/g;
      let pair;
      while ((pair = pairs.exec(d)) !== null) {points.push([Number(pair[1]), Number(pair[2])]);}
    }
    for (const [x, y] of points) {
      const r = Math.hypot(x, y);
      assert.ok(r <= reach * spec.scale + 0.6, `a point ${r.toFixed(1)} out reaches past the ${spec.scale} scale`);
    }
    const angle = ([x, y]) => Math.atan2(x, -y) * 180 / Math.PI;
    const radius = ([x, y]) => Math.hypot(x, y);
    const maxR = Math.max(...points.map(radius));
    assert.ok(points.some(p => radius(p) >= maxR * 0.92 && Math.abs(angle(p)) < 6),
              "an arm reaches its point");
    // The landing corners sit a few degrees either side of the neighbour's axis: the
    // spiral's last facet ends on the axis, but the quad's corners are offset from it
    // by the facet's own half width at that radius.
    if (points.some(p => Math.abs(angle(p) - 60) < 6 && radius(p) > maxR * 0.3)) {joined++;}
    // The join is straight facets, not a smooth arc. Every element of a crystal is a
    // tapered quad; a many-sided path is the ribbon that made this habit read as a
    // flower, because the curve was the only round thing on it.
    for (const d of paths) {
      const corners = (d.match(/L/g) || []).length + 1;
      assert.ok(corners <= 13, `${corners}-sided element — a spiral facet is a quad, not an arc`);
    }
  }
  assert.ok(joined >= seeds * helpers.SPIRAL_CHANCE * 0.6,
            `${joined} of ${seeds} needle crystals join their arms to their neighbour`);
  assert.ok(joined < seeds, "and not every one does — an unjoined crystal is not a defect");
});

test("the size envelope over a flake's travel has no slope break", () => {
  const helpers = api();
  const rnd = helpers.prng(9);
  const bounds = {width: 400, height: 900};
  const flake = helpers.createFlake(bounds, 0, rnd);
  // Shortened so the whole envelope is walked in a few dozen steps; the second
  // difference scales with the square of the travel per step, so the bound below is
  // written for this budget, not for a real viewport.
  flake.maxDistance = 200;
  const sizes = [];
  for (let i = 0; i < 400 && !flake.isDying; i++) {
    helpers.stepFlake(flake, bounds, rnd);
    sizes.push(flake.size);
  }
  assert.ok(sizes.length > 100, `${sizes.length} steps of travel measured`);
  let jerk = 0;
  for (let i = 1; i < sizes.length - 1; i++) {
    jerk = Math.max(jerk, Math.abs(sizes[i + 1] - 2 * sizes[i] + sizes[i - 1]));
  }
  // Four straight segments with a slope change at each quarter measured 1.1e-2 here;
  // one smooth curve measures a fraction of that.
  assert.ok(jerk < 3e-3, `largest second difference is ${jerk.toExponential(1)} px a step`);
});

test("a resize retargets travel budgets, so flakes recycle at the new height", () => {
  const run = openPrepared({seed: 17, height: 900});
  run.w.send({type: "view", width: 800, height: 200, dpr: 1});
  const ack = run.w.take("viewed");
  assert.ok(ack, "the worker confirms the resize, which is when the page shows the canvas again");
  assert.equal(ack.height, 200);
  assert.equal(ack.width, 800);
  const frame = run.ticks(900);
  assert.equal(frame.count, run.ready.count, "a resize does not change the flake count");
  for (let i = 0; i < frame.count; i++) {
    assert.ok(frame.buf[i * 8 + 1] <= 200 + 64, `flake ${i} respects the shorter viewport`);
  }
});

test("the quality controller's count is the count the next frame holds", () => {
  const run = openPrepared({seed: 21});
  run.w.send({type: "quality", count: 6});
  assert.equal(run.ticks(2).count, 6);
  run.w.send({type: "quality", count: run.ready.count});
  assert.equal(run.ticks(2).count, run.ready.count, "flakes are given back up to the viewport's count");
  run.w.send({type: "quality", count: 9999});
  assert.equal(run.ticks(2).count, run.ready.count, "never more than the atlas has slots for");
});

test("a paused worker stays silent until it is released", () => {
  const run = openPrepared({seed: 2});
  run.w.send({type: "visible", visible: false});
  run.w.send({type: "tick", t: run.at() + FRAME_MS});
  assert.deepEqual(run.w.messages(), [], "a hidden tab gets no frames");
  run.w.send({type: "visible", visible: true});
  run.w.send({type: "tick", t: run.at() + FRAME_MS * 2});
  assert.ok(run.w.take("frame"), "a visible tab gets them again");
  run.w.send({type: "stop"});
  run.w.send({type: "tick", t: run.at() + FRAME_MS * 3});
  assert.deepEqual(run.w.messages(), [], "stop halts it");
});

test("the worker reports the rate it was driven at", () => {
  const run = openPrepared({seed: 8});
  run.ticks(130);
  const stats = run.w.take("stats");
  assert.ok(stats, "a stats message arrives every couple of seconds");
  assert.ok(stats.fps > 55 && stats.fps < 65, `measured ${stats.fps} fps over 60Hz ticks`);
  assert.ok(stats.count >= 1);
});

test("varyColor stays inside the colour range and honours the hex form", () => {
  const helpers = api();
  assert.deepEqual(Array.from(helpers.parseHex("#78c3f2")), [120, 195, 242]);
  assert.deepEqual(Array.from(helpers.parseHex("78c3f2")), [120, 195, 242]);
  assert.equal(helpers.parseHex("not a colour"), null);
  const rnd = helpers.prng(9);
  for (let i = 0; i < 200; i++) {
    const color = helpers.varyColor([120, 195, 242], 10, rnd);
    const parts = color.match(/^rgb\((\d+),(\d+),(\d+)\)$/);
    assert.ok(parts, `an rgb() string: ${color}`);
    for (const part of parts.slice(1)) {
      const value = Number(part);
      assert.ok(value >= 0 && value <= 255, `channel ${value} is in range`);
    }
  }
});

test("each frame is its own transferable buffer", () => {
  const run = openPrepared({seed: 13});
  const first = run.ticks(1);
  const second = run.ticks(1, FRAME_MS * 2);
  assert.notEqual(first.buf.buffer, second.buf.buffer,
                  "a buffer handed to the page is never painted into again");
});
