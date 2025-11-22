/* ProgressX 1.1, 2025-09-09
 * https://github.com/ryadpasha/progressx
 * Copyright (c) 2018 Ryad Pasha
 * Licensed under the MIT License
 * Optimizations and theme support by dr|z3d, 2024-2025
 */

function initProgressX(window, document) {
  "use strict";

  if (!window.requestAnimationFrame) {
    window.progressx = { config: () => {}, show: () => {}, progress: () => {}, hide: () => {} };
    return;
  }

  let canvas, context, gradient = null;
  let fadeTimer = null;
  let currentProgress = 0, isVisible = false;
  let lastColors = null;
  let frameRequested = false;
  let animationFrameId = null;
  let animationLocked = false;

  const defaultColors = {"0.0": "rgba(220,48,16,.8)", "1.0": "rgba(255,96,0,.8)"};

  const options = {
    autoRun: true,
    barThickness: 3,
    barColors: {
      default: defaultColors,
      classic: {"0.0": "rgba(48,64,192,.7)", "1.0": "rgba(48,64,255,.7)"},
      midnight: {"0.0": "rgba(72,0,72,.8)", "1.0": "rgba(160,0,160,.8)"},
      current: defaultColors
    }
  };

  let currentColorSetName = "default";

  function createCanvas() {
    if (canvas) return;
    canvas = document.createElement("canvas");
    canvas.id = "pageloader";
    canvas.style.cssText = `
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      margin: 0;
      padding: 0;
      z-index: 100001;
      display: none;
      opacity: 1;
    `;
    injectCanvas();
    context = canvas.getContext("2d");
    window.addEventListener("resize", onResized, { passive: true });
  }

  function injectCanvas() {
    const html = document.documentElement;
    html.firstChild
      ? html.insertBefore(canvas, html.firstChild)
      : html.appendChild(canvas);
  }

  let ticking = false;
  function onResized() {
    if (!ticking) {
      requestAnimationFrame(() => {
        draw();
        ticking = false;
      });
      ticking = true;
    }
  }

  function colorsEqual(a, b) {
    if (!a || !b) return false;
    const keysA = Object.keys(a), keysB = Object.keys(b);
    if (keysA.length !== keysB.length) return false;
    for (let key of keysA) {
      if (a[key] !== b[key]) return false;
    }
    return true;
  }

  function draw() {
    if (!canvas || !context) return;

    const width = window.innerWidth;
    const height = options.barThickness;
    const colors = options.barColors[currentColorSetName] || options.barColors.default;
    options.barColors.current = colors;

    if (canvas.width !== width) canvas.width = width;
    if (canvas.height !== height) canvas.height = height;

    if (!colorsEqual(colors, lastColors)) {
      gradient = context.createLinearGradient(0, 0, width, 0);
      for (const stop in colors) {
        gradient.addColorStop(parseFloat(stop), colors[stop]);
      }
      lastColors = { ...colors };
    }

    context.clearRect(0, 0, width, height);
    context.lineWidth = height;
    context.beginPath();
    context.moveTo(0, height / 2);
    context.lineTo(Math.ceil(currentProgress * width), height / 2);
    context.strokeStyle = gradient;
    context.stroke();
  }

  function runProgressLoop() {
    if (animationLocked) return;
    animationLocked = true;

    function animate() {
      const increment = 0.05 * Math.pow(1 - Math.sqrt(currentProgress), 2);
      if (currentProgress < 1) {
        progressxProgress(currentProgress + increment, false);
        requestAnimationFrame(animate);
      } else {
        animationLocked = false;
      }
    }

    requestAnimationFrame(animate);
  }

  function progressxShow(colorSet) {
    if (isVisible || animationLocked) return;
    isVisible = true;

    if (fadeTimer) {
      cancelAnimationFrame(fadeTimer);
      fadeTimer = null;
    }

    if (animationFrameId) {
      cancelAnimationFrame(animationFrameId);
      animationFrameId = null;
    }

    if (!canvas) createCanvas();
    canvas.style.opacity = "1";
    canvas.style.display = "block";
    currentProgress = 0;
    currentColorSetName = colorSet || "default";
    requestAnimationFrame(draw);
    animationLocked = true;

    if (options.autoRun) runProgressLoop();
  }

  function progressxHide() {
    if (!isVisible) return;
    isVisible = false;

    let opacity = 1;
    function fadeLoop() {
      if (progressxProgress("+0.1", false) >= 1) {
        opacity -= 0.045;
        canvas.style.opacity = opacity.toString();
        if (opacity <= 0.2) {
          canvas.style.display = "none";
          fadeTimer = null;
          window.removeEventListener("resize", onResized);
          animationLocked = false;
          return;
        }
      }
      fadeTimer = requestAnimationFrame(fadeLoop);
    }

    fadeLoop();
  }

  function progressxProgress(value, animate = true) {
    if (!canvas || !context) return currentProgress;

    const oldValue = currentProgress;

    if (typeof value === "string") {
      const op = value[0], num = parseFloat(value.slice(1));
      value = op === "+" ? currentProgress + num : op === "-" ? currentProgress - num : num;
    }

    const target = Math.min(1, Math.max(0, value));

    if (!animate || target === oldValue) {
      currentProgress = target;
      if (!frameRequested) {
        frameRequested = true;
        requestAnimationFrame(() => {
          draw();
          frameRequested = false;
        });
      }
      return currentProgress;
    }

    if (animationLocked) return currentProgress;

    animationLocked = true;

    if (animationFrameId) cancelAnimationFrame(animationFrameId);

    const duration = 300;
    const start = performance.now();

    const animateProgress = (now) => {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      const easeOut = 1 - Math.pow(1 - progress, 3);
      currentProgress = oldValue + (target - oldValue) * easeOut;
      draw();

      if (progress < 1) {
        animationFrameId = requestAnimationFrame(animateProgress);
      } else {
        currentProgress = target;
        animationLocked = false;
        animationFrameId = null;
        draw();
      }
    };

    animationFrameId = requestAnimationFrame(animateProgress);
    return currentProgress;
  }

  function progressxConfig(opts) {
    if (!opts) return;
    Object.assign(options, opts);
    if (opts.barColors) {
      Object.assign(options.barColors, opts.barColors);
    }
    options.barColors.current = options.barColors[currentColorSetName] || options.barColors.default;
  }

  window.progressx = { config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide };
}

initProgressX(window, document);

window.addEventListener("load", () => { progressx.hide(); });