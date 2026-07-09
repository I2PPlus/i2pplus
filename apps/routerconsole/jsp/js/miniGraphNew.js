/**
 * @module miniGraphNew
 * @description Dual-baseline minigraph renderer for the I2P+ console sidebar.
 * Renders inbound traffic (inverted, top half) and outbound traffic (normal, bottom half)
 * with cardinal spline interpolation and a subtle glow effect. Replaces the RRD4J
 * SVG-based renderer when routerconsole.graphNewRenderer is enabled.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/** @type {number} Canvas width in pixels */
const WIDTH = 245;
/** @type {number} Canvas height in pixels */
const HEIGHT = 50;
/** @type {number} Padding inset in pixels */
const PAD = 4;
/** @type {number} Drawing area width (excluding padding) */
const DRAW_W = WIDTH - PAD * 2;
/** @type {number} Drawing area height (excluding padding) */
const DRAW_H = HEIGHT - PAD * 2;
/** @type {number} Center Y coordinate — baseline for both halves (padded) */
const CENTER_Y = PAD + DRAW_H / 2;
/** @type {number} Number of data points */

/** @type {number} Sidebar refresh interval in ms, matching the legacy renderer */
const POLL_INTERVAL = refresh != null ? Math.max(refresh * 1000, 1000) : 3000;

/** @type {?HTMLCanvasElement} */
let graphCanvas = null;
/** @type {?CanvasRenderingContext2D} */
let graphCtx = null;
/** @type {?HTMLCanvasElement} Offscreen canvas for double-buffering */
let offscreenCanvas = null;
/** @type {?CanvasRenderingContext2D} */
let offscreenCtx = null;
/** @type {?number[]} Shift buffer for rx data */
let rxBuffer = null;
/** @type {?number[]} Shift buffer for tx data */
let txBuffer = null;
/** @type {string} sessionStorage key for shift buffers */
const BUFFER_KEY = "minigraph_buffers";
/** @type {number} Target buffer length for smooth scrolling (~20 min at 3s resolution) */
const TARGET_BUFFER = 400;
/** @type {number} Timestamp of last buffer shift (ms) — prevents double-shift from dual callers */
let lastShiftTime = 0;

/**
 * Restores shift buffers from sessionStorage if available and data-minutes matches.
 * Accepts any buffer length >= 2 (old 20-point or new 400-point buffers).
 * @function restoreBuffers
 * @param {number} minutes - Expected time period
 * @returns {boolean} True if buffers were restored
 */
function restoreBuffers(minutes) {
    try {
        const saved = JSON.parse(sessionStorage.getItem(BUFFER_KEY));
        if (saved && saved.minutes === minutes && saved.rx && saved.tx &&
            saved.rx.length >= TARGET_BUFFER / 2 && saved.tx.length >= TARGET_BUFFER / 2) {
            rxBuffer = saved.rx;
            txBuffer = saved.tx;
            return true;
        }
    } catch (e) { /* ignored */ }
    return false;
}

/**
 * Persists shift buffers to sessionStorage.
 * Caps stored length at TARGET_BUFFER to avoid bloat.
 * @function saveBuffers
 * @param {number} minutes - Time period
 */
function saveBuffers(minutes) {
    try {
        // Cap stored buffers to TARGET_BUFFER to avoid sessionStorage bloat
        const rxStore = rxBuffer.length > TARGET_BUFFER ? rxBuffer.slice(-TARGET_BUFFER) : rxBuffer;
        const txStore = txBuffer.length > TARGET_BUFFER ? txBuffer.slice(-TARGET_BUFFER) : txBuffer;
        sessionStorage.setItem(BUFFER_KEY, JSON.stringify({
            minutes,
            rx: rxStore,
            tx: txStore
        }));
    } catch (e) { /* ignored */ }
}

/**
 * Reads a CSS custom property from the document root.
 * @function getCSSVar
 * @param {string} name - CSS variable name (e.g. "--minigraph_in")
 * @returns {string} The computed value, or empty string if not set
 */
function getCSSVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/**
 * Creates a Canvas fill style from a CSS variable value.
 * Supports flat colors ("#0cc", "rgba(...)") and CSS linear-gradient syntax.
 * @function parseFillStyle
 * @param {CanvasRenderingContext2D} ctx - Canvas rendering context
 * @param {string} cssValue - CSS color or linear-gradient string
 * @param {number} startY - Gradient start Y coordinate (used only for gradients)
 * @param {number} endY - Gradient end Y coordinate (used only for gradients)
 * @returns {string|CanvasGradient} A canvas-compatible fill style
 */
function parseFillStyle(ctx, cssValue, startY, endY) {
    if (!cssValue || !cssValue.includes("gradient")) {return cssValue || "transparent";}
    const grad = ctx.createLinearGradient(0, startY, 0, endY);
    const stops = cssValue.match(/#[0-9a-f]{3,8}|rgba?\([^)]+\)|transparent/gi) || [];
    const n = stops.length;
    stops.forEach((color, i) => {
        grad.addColorStop(n > 1 ? i / (n - 1) : 0, color);
    });
    return grad;
}

/**
 * Draws a cardinal spline through a set of points on a canvas context.
 * Based on Paul Bourke's cardinal spline implementation.
 * @function drawSpline
 * @param {CanvasRenderingContext2D} ctx - Canvas rendering context
 * @param {Array<{x: number, y: number}>} pts - Array of points to interpolate through
 * @param {boolean} close - Whether to close the path back to the first point
 */
function drawSpline(ctx, pts, close, tension) {
    const n = pts.length;
    if (n < 2) {return;}
    const t = tension || 0.5;

    ctx.moveTo(pts[0].x, pts[0].y);

    for (let i = 0; i < n - 1; i++) {
        const p0 = i > 0 ? pts[i - 1] : pts[0];
        const p1 = pts[i];
        const p2 = pts[i + 1];
        const p3 = i + 2 < n ? pts[i + 2] : pts[n - 1];

        const cp1x = p1.x + (p2.x - p0.x) / (6 * t);
        const cp1y = p1.y + (p2.y - p0.y) / (6 * t);
        const cp2x = p2.x - (p3.x - p1.x) / (6 * t);
        const cp2y = p2.y - (p3.y - p1.y) / (6 * t);

        ctx.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y);
    }

    if (close) {
        ctx.closePath();
    }
}

/**
 * Converts a data value to a Y coordinate for the top half (inbound, inverted).
 * Value 0 maps to CENTER_Y (center), max value maps to PAD (top edge).
 * @function valueToYIn
 * @param {number} value - The data value
 * @param {number} maxVal - The maximum value in the dataset
 * @returns {number} Y coordinate
 */
function valueToYIn(value, maxVal) {
    if (maxVal <= 0) {return CENTER_Y;}
    return CENTER_Y - (value / maxVal) * (CENTER_Y - PAD);
}

/**
 * Converts a data value to a Y coordinate for the bottom half (outbound, normal).
 * Value 0 maps to CENTER_Y (center), max value maps to HEIGHT - PAD (bottom edge).
 * @function valueToYOut
 * @param {number} value - The data value
 * @param {number} maxVal - The maximum value in the dataset
 * @returns {number} Y coordinate
 */
function valueToYOut(value, maxVal) {
    if (maxVal <= 0) {return CENTER_Y;}
    return CENTER_Y + (value / maxVal) * (HEIGHT - PAD - CENTER_Y);
}

/**
 * Converts a data value to a Y coordinate for overlay mode (both lines from top).
 * Value 0 maps to PAD (top edge), max value maps to HEIGHT - PAD (bottom edge).
 * @function valueToYOverlay
 * @param {number} value - The data value
 * @param {number} maxVal - The maximum value in the dataset
 * @returns {number} Y coordinate
 */
function valueToYOverlay(value, maxVal) {
    if (maxVal <= 0) {return PAD;}
    return PAD + (value / maxVal) * DRAW_H;
}

/**
 * Parses a comma-separated string of numeric values into an array of numbers.
 * @function parseValues
 * @param {string} str - Comma-separated values
 * @returns {number[]} Parsed numeric values
 */
function parseValues(str) {
    if (!str) {return [];}
    return str.split(",").map(Number);
}

/**
 * Linearly interpolates a low-resolution array into a high-resolution array.
 * Each pair of adjacent points is expanded to `pointsPerStep` sub-points.
 * @function interpolate
 * @param {number[]} lowRes - Low-resolution data (e.g. 20 RRD points at 1-min intervals)
 * @param {number} pointsPerStep - Number of output points per input interval (e.g. 3 for 20s resolution)
 * @returns {number[]} Interpolated high-resolution array
 */
function interpolate(lowRes, pointsPerStep) {
    if (!lowRes || lowRes.length < 2) {return lowRes || [];}
    if (pointsPerStep < 1) {pointsPerStep = 1;}
    const out = [];
    for (let i = 0; i < lowRes.length - 1; i++) {
        const a = lowRes[i];
        const b = lowRes[i + 1];
        for (let j = 0; j < pointsPerStep; j++) {
            const t = j / pointsPerStep;
            out.push(a + (b - a) * t);
        }
    }
    out.push(lowRes[lowRes.length - 1]);
    return out;
}

/**
 * Draws a single half of the graph (inbound or outbound).
 * Supports two-pass rendering: pass="fill" draws only the fill,
 * pass="stroke" draws only glow + line. Omit for single-pass (all 3 layers).
 * @function drawHalf
 * @param {CanvasRenderingContext2D} ctx - Canvas rendering context
 * @param {number[]} values - Array of data values
 * @param {number} maxVal - Maximum value for scaling
 * @param {string} lineColor - Stroke color for the line
 * @param {string} fillColor - CSS color or linear-gradient string for the area fill
 * @param {Function} yMapper - Function to map value to Y coordinate
 * @param {boolean} fillDown - Whether fill goes downward from the curve (true for outbound)
 * @param {number} baselineY - Y coordinate for fill closure
 * @param {?string} blendMode - Canvas globalCompositeOperation for fill (null for none)
 * @param {?string} pass - "fill" for fill-only, "stroke" for glow+line only, null for all
 */
function drawHalf(ctx, values, maxVal, lineColor, fillColor, yMapper, fillDown, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, baselineY, blendMode, pass) {
    if (values.length < 2) {return;}
    if (baselineY == null) {baselineY = CENTER_Y;}

    const stepX = DRAW_W / (values.length - 1);
    const n = values.length;
    const fpts = values.map((v, i) => ({
        x: rtl ? PAD + (n - 1 - i) * stepX : PAD + i * stepX,
        y: yMapper(v, maxVal)
    }));
    const pts = fpts.map(p => ({x: p.x, y: p.y}));
    pts[0].x += rtl ? stepX * 0.5 : -stepX * 0.5;
    pts[pts.length - 1].x += rtl ? -stepX * 0.5 : stepX * 0.5;

    ctx.save();
    ctx.beginPath();
    ctx.rect(0, 0, WIDTH, HEIGHT);
    ctx.clip();

    const t = tension || 0.5;

    // Fill pass
    if (pass !== "stroke") {
        const fillStartX = pts[0].x;
        const fillEndX = pts[pts.length - 1].x;
        ctx.fillStyle = parseFillStyle(ctx, fillColor, baselineY, fillDown ? HEIGHT : PAD);
        if (blendMode) {ctx.globalCompositeOperation = blendMode;}
        ctx.beginPath();
        ctx.moveTo(fillStartX, baselineY);
        ctx.lineTo(fillStartX, pts[0].y);
        for (let i = 0; i < pts.length - 1; i++) {
            const p0 = i > 0 ? pts[i - 1] : pts[0];
            const p1 = pts[i];
            const p2 = pts[i + 1];
            const p3 = i + 2 < pts.length ? pts[i + 2] : pts[pts.length - 1];
            const cp1x = p1.x + (p2.x - p0.x) / (6 * t);
            const cp1y = p1.y + (p2.y - p0.y) / (6 * t);
            const cp2x = p2.x - (p3.x - p1.x) / (6 * t);
            const cp2y = p2.y - (p3.y - p1.y) / (6 * t);
            ctx.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y);
        }
        ctx.lineTo(fillEndX, baselineY);
        ctx.closePath();
        ctx.fill();
        if (blendMode) {ctx.globalCompositeOperation = "source-over";}
    }

    // Stroke pass (glow + line)
    if (pass !== "fill") {
        ctx.strokeStyle = lineColor;
        ctx.lineWidth = glowWidth;
        ctx.globalAlpha = glowAlpha;
        ctx.shadowColor = lineColor;
        ctx.shadowBlur = glowBlur;
        ctx.beginPath();
        drawSpline(ctx, pts, false, t);
        ctx.stroke();
        ctx.globalAlpha = 1;
        ctx.shadowBlur = 0;

        ctx.strokeStyle = lineColor;
        ctx.lineWidth = lineWidth;
        ctx.lineJoin = "round";
        ctx.lineCap = "round";
        ctx.beginPath();
        drawSpline(ctx, pts, false, t);
        ctx.stroke();
    }

    ctx.restore();
}

/**
 * Main render function. Reads data from the canvas element attributes
 * and draws the dual-baseline graph.
 * @function renderNewGraph
 * @returns {void}
 */
function renderNewGraph() {
    const el = document.getElementById("minigraph");
    if (!el) {return;}
    if (el !== graphCanvas) {
        graphCanvas = el;
        graphCtx = null;
    }

    const rxStr = graphCanvas.dataset.rx;
    const txStr = graphCanvas.dataset.tx;
    const minutes = parseInt(getCSSVar("--minigraph_minutes"), 10) || parseInt(graphCanvas.dataset.minutes, 10) || 20;

    // Parse server data, extract live value (last element), update shift buffer
    if (rxStr && txStr) {
        const rxAll = parseValues(rxStr);
        const txAll = parseValues(txStr);
        const liveRx = rxAll.pop();
        const liveTx = txAll.pop();
        if (rxBuffer === null) {
            // Try sessionStorage first, then init from server data (RRD + live).
            // Interpolate to fill a TARGET_BUFFER-length buffer for smooth
            // scrolling from the start; shift+push gradually replaces the
            // interpolated values with real live data over time.
            if (!restoreBuffers(minutes)) {
                const pointsPerStep = Math.max(Math.round(TARGET_BUFFER / rxAll.length), 1);
                rxBuffer = interpolate(rxAll, pointsPerStep);
                txBuffer = interpolate(txAll, pointsPerStep);
                rxBuffer.push(liveRx);
                txBuffer.push(liveTx);
            }
            lastShiftTime = Date.now();
        } else {
            const now = Date.now();
            // Normal scroll: shift oldest, append new live value.
            // No gap-fill branch — once the buffer scrolls past the
            // interpolated initial data it stays real; re-interpolating
            // from the server would reset scroll position.
            if (now - lastShiftTime >= POLL_INTERVAL) {
                rxBuffer.shift();
                txBuffer.shift();
                rxBuffer.push(liveRx);
                txBuffer.push(liveTx);
                lastShiftTime = now;
            } else {
                rxBuffer[rxBuffer.length - 1] = liveRx;
                txBuffer[txBuffer.length - 1] = liveTx;
            }
        }
        saveBuffers(minutes);
    }
    if (!rxBuffer || !txBuffer) {return;}

    const rxValues = rxBuffer;
    const txValues = txBuffer;
    if (rxValues.length < 2 && txValues.length < 2) {return;}

    // Lazily create offscreen canvas for double-buffering
    if (!offscreenCanvas) {
        offscreenCanvas = document.createElement("canvas");
        offscreenCanvas.width = WIDTH;
        offscreenCanvas.height = HEIGHT;
        offscreenCtx = offscreenCanvas.getContext("2d");
    }

    if (!graphCtx) {
        graphCtx = graphCanvas.getContext("2d");
    }

    const rxMax = Math.max(...rxValues, 1);
    const txMax = Math.max(...txValues, 1);

    // Read theme colors from CSS variables
    const rxColor = getCSSVar("--minigraph_in") || "#0cc";
    const txColor = getCSSVar("--minigraph_out") || "#f90";
    const rxFill = getCSSVar("--minigraph_in_fill") || "rgba(0,204,204,.15)";
    const txFill = getCSSVar("--minigraph_out_fill") || "rgba(255,153,0,.15)";
    const rtl = window.graphDirection === "rtl";
    const glowWidth = parseFloat(getCSSVar("--minigraph_glow_width")) || 4;
    const glowAlpha = parseFloat(getCSSVar("--minigraph_glow_alpha")) || 0.3;
    const glowBlur = parseFloat(getCSSVar("--minigraph_glow_blur")) || 6;
    const lineWidth = parseFloat(getCSSVar("--minigraph_line_width")) || 1.5;
    const tension = parseFloat(getCSSVar("--minigraph_tension")) || 0.5;

    // Split mode: true = split display (inbound top, outbound bottom), false = overlay
    const split = graphCanvas.dataset.split !== "0";
    const blendMode = getCSSVar("--minigraph_overlay_blend") || "screen";

    // Draw to offscreen canvas
    offscreenCtx.clearRect(0, 0, WIDTH, HEIGHT);
    drawGrid(offscreenCtx, minutes, split);
    if (split) {
        // Split: single-pass, no blend needed
        drawHalf(offscreenCtx, txValues, txMax, txColor, txFill, valueToYIn, false, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, CENTER_Y, null, null);
        drawHalf(offscreenCtx, rxValues, rxMax, rxColor, rxFill, valueToYOut, true, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, CENTER_Y, null, null);
    } else {
        // Overlay: two-pass — fills blended first, then strokes on top
        drawHalf(offscreenCtx, txValues, txMax, txColor, txFill, valueToYOverlay, false, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, HEIGHT, blendMode, "fill");
        drawHalf(offscreenCtx, rxValues, rxMax, rxColor, rxFill, valueToYOverlay, false, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, HEIGHT, blendMode, "fill");
        drawHalf(offscreenCtx, txValues, txMax, txColor, txFill, valueToYOverlay, false, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, HEIGHT, null, "stroke");
        drawHalf(offscreenCtx, rxValues, rxMax, rxColor, rxFill, valueToYOverlay, false, rtl, glowWidth, glowAlpha, glowBlur, lineWidth, tension, HEIGHT, null, "stroke");
    }

    // Copy to visible canvas in one operation
    graphCtx.clearRect(0, 0, WIDTH, HEIGHT);
    graphCtx.drawImage(offscreenCanvas, 0, 0);
}

/**
 * Draws a subtle dotted grid behind the graph data.
 * Split mode: horizontal line at center baseline, vertical lines at adaptive intervals.
 * Overlay mode: 3 horizontal grid lines (top, middle, bottom), vertical lines at adaptive intervals.
 * @function drawGrid
 * @param {CanvasRenderingContext2D} ctx - Canvas rendering context
 * @param {number} minutes - Total time period in minutes
 * @param {boolean} split - True for split mode, false for overlay
 */
function drawGrid(ctx, minutes, split) {
    ctx.save();
    ctx.strokeStyle = getCSSVar("--minigraph_grid") || "rgba(128,128,128,.15)";
    ctx.lineWidth = 0.5;
    ctx.setLineDash([2, 3]);

    // Horizontal lines
    ctx.beginPath();
    if (split) {
        // Single center baseline
        ctx.moveTo(PAD, CENTER_Y);
        ctx.lineTo(PAD + DRAW_W, CENTER_Y);
    } else {
        // 3 horizontal lines: top, middle, bottom
        for (const frac of [0.25, 0.5, 0.75]) {
            const y = PAD + frac * DRAW_H;
            ctx.moveTo(PAD, y);
            ctx.lineTo(PAD + DRAW_W, y);
        }
    }
    ctx.stroke();

    // Vertical lines — use clean intervals (5, 10, 15, 20, 30, 60 min)
    // targeting ~8–10 lines; falls back to raw division for short periods
    let stepMin;
    let cols;
    if (minutes > 40) {
        const intervals = [5, 10, 15, 20, 30, 60];
        stepMin = minutes / 10;
        for (const iv of intervals) {
            if (iv >= stepMin) {stepMin = iv; break;}
        }
        cols = Math.round(minutes / stepMin);
    } else {
        cols = Math.min(Math.round(minutes / 2), 10);
        stepMin = minutes / cols;
    }
    const stepX = DRAW_W / cols;
    for (let i = 1; i < cols; i++) {
        ctx.beginPath();
        ctx.moveTo(PAD + i * stepX, PAD);
        ctx.lineTo(PAD + i * stepX, PAD + DRAW_H);
        ctx.stroke();
    }

    ctx.restore();
}

/**
 * Initializes the new minigraph renderer. Re-renders on a polling interval
 * to handle canvas element replacement during sidebar XHR refreshes.
 * @function initNewGraph
 * @returns {void}
 */
function initNewGraph() {
    graphCanvas = document.getElementById("minigraph");
    if (!graphCanvas) {return;}

    // Initial render
    renderNewGraph();

    // Re-render immediately after full sidebar replacement (refreshAll)
    document.addEventListener("sidebarRefreshed", () => {
        const el = document.getElementById("minigraph");
        if (el !== graphCanvas) {
            graphCanvas = el;
            graphCtx = null;
        }
        if (graphCanvas) {renderNewGraph();}
    });

    // Poll for canvas element replacement or data attribute changes.
    // The sidebar XHR refresh may replace the canvas element entirely
    // (via refreshAll()), which destroys any MutationObserver attached to it.
    const pollGraph = () => {
        const el = document.getElementById("minigraph");
        if (el !== graphCanvas) {
            graphCanvas = el;
            graphCtx = null;
        }
        if (graphCanvas && rxBuffer !== null) {renderNewGraph();}
    };
    let pollIntervalId = setInterval(pollGraph, POLL_INTERVAL);

    document.addEventListener("visibilitychange", () => {
        if (document.hidden) {
            clearInterval(pollIntervalId);
        } else {
            // After a visibility gap the buffer is behind real time.
            // Null it to force a clean re-init from the next sidebar
            // refresh data, avoiding the long flat line from missed
            // intermediate values.
            rxBuffer = null;
            txBuffer = null;
            pollIntervalId = setInterval(pollGraph, POLL_INTERVAL);
        }
    });
}

export { initNewGraph, renderNewGraph };
window.renderNewGraph = renderNewGraph;
