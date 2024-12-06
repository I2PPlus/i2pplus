/* ProgressX 1.1, 2024-04-15
 * https://github.com/ryadpasha/progressx
 * Copyright (c) 2018 Ryad Pasha
 * Licensed under the MIT License
 * Optimizations and theme support by dr|z3d, 2024 */

function initProgressX(window, document) {
  "use strict";

  let lastTime = 0;

  if (!window.requestAnimationFrame) {
    window.requestAnimationFrame = function(callback) {
      const currTime = Date.now();
      const timeToCall = Math.max(0, 16 - (currTime - lastTime));
      const id = window.setTimeout(() => callback(currTime + timeToCall), timeToCall);
      lastTime = currTime + timeToCall;
      return id;
    };
  }

  if (!window.cancelAnimationFrame) {
    window.cancelAnimationFrame = function(id) { clearTimeout(id); };
  }

  let canvas, context, progressTimerId, fadeTimerId, currentProgress = 0, showing = false, options = {
    autoRun: true,
    barThickness: 3,
    barColors: {
      default: {
        "0.0": "rgba(220, 48, 16, .8)",
        "1.0": "rgba(255, 96, 0, .8)"
      },
      classic: {
        "0.0": "rgba(48, 64, 192, .7)",
        "1.0": "rgba(48, 64, 255, .7)"
      },
      midnight: {
        "0.0": "rgba(72, 0, 72, .8)",
        "1.0": "rgba(160, 0, 160, .8)"
      }
    }
  };

  function createCanvas() {
    canvas = document.createElement("canvas");
    canvas.id = "pageloader";
    canvas.style.position = "fixed";
    canvas.style.top = canvas.style.left = canvas.style.right = canvas.style.margin = canvas.style.padding = 0;
    canvas.style.zIndex = 100001;
    canvas.style.display = "none";
    document.body.appendChild(canvas);
    context = canvas.getContext("2d");
    window.addEventListener("resize", repaint);
  }

  function repaint() {
    if (!canvas) return;
    const { barThickness, barColors: { current: lineColors } } = options;
    const width = window.innerWidth;
    const height = barThickness;

    canvas.width = width;
    canvas.height = height;

    if (lineColors) {
      const lineGradient = context.createLinearGradient(0, 0, width, 0);
      for (const stop in lineColors) {
        lineGradient.addColorStop(parseFloat(stop), lineColors[stop]);
      }

      context.clearRect(0, 0, width, height);
      context.lineWidth = height;
      context.beginPath();
      context.moveTo(0, height / 2);
      context.lineTo(Math.ceil(currentProgress * width), height / 2);
      context.strokeStyle = lineGradient;
      context.stroke();
    }
  }

  function addEvent(elem, type, handler) {
    elem.addEventListener(type, handler);
  }

  function progressxConfig(opts) {
    options = {
      ...options,
      ...opts,
      barColors: {
        ...options.barColors,
        ...(opts.barColors || {})
      }
    };
  }

  function runProgressLoop() {
    progressTimerId = window.requestAnimationFrame(() => {
      progressxProgress("+" + (0.05 * Math.pow(1 - Math.sqrt(currentProgress), 2)));
      runProgressLoop();
    });
  }

  function progressxShow(colorSet) {
    if (showing) return;
    showing = true;
    if (fadeTimerId !== null) window.cancelAnimationFrame(fadeTimerId);
    if (!canvas) createCanvas();
    canvas.style.opacity = 1;
    canvas.style.display = "block";
    currentProgress = 0;
    options.barColors.current = options.barColors[colorSet] || options.barColors.default;
    if (options.autoRun) {
      runProgressLoop();
    }
  }

  function progressxProgress(to) {
    if (typeof to === "string") {
      to = (to.startsWith("+") || to.startsWith("-") ? currentProgress : 0) + parseFloat(to);
    }
    currentProgress = Math.min(1, Math.max(0, to));
    requestAnimationFrame(repaint);
    return currentProgress;
  }

  function progressxHide() {
    if (!showing) return;
    showing = false;
    if (progressTimerId !== null) {
      window.cancelAnimationFrame(progressTimerId);
      progressTimerId = null;
    }
    (function loop() {
      if (progressxProgress("+0.1") >= 1) {
        canvas.style.opacity -= 0.25;
        if (canvas.style.opacity <= 0.25) {
          canvas.style.display = "none";
          fadeTimerId = null;
          window.removeEventListener("resize", repaint);
          return;
        }
      }
      fadeTimerId = window.requestAnimationFrame(loop);
    })();
  }

  if (typeof module === "object" && typeof module.exports === "object") {
    module.exports = { config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide };
  } else if (typeof define === "function" && define.amd) {
    define(() => ({ config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide }));
  } else {
    window.progressx = { config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide };
  }
}

initProgressX(window, document);

document.addEventListener("DOMContentLoaded", () => {
  document.documentElement.removeAttribute("style");
  progressx.hide();
});