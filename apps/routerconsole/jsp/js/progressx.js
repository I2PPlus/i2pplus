/* ProgressX 1.1, 2024-04-15
 * https://github.com/ryadpasha/progressx
 * Copyright (c) 2018 Ryad Pasha
 * Licensed under the MIT License
 * Modifications and optimizations by dr|z3d, 2024 */

(function(window, document) {
  "use strict";

  var canvas, progressTimerId, fadeTimerId, currentProgress, showing, options = {
    autoRun: true,
    barThickness: 3,
    barColors: {
      default: {
        0.0: "rgba(220, 48, 16, .8)",
        1.0: "rgba(255, 96, 0, .8)"
      },
      midnight: {
        0.0: "rgba(72, 0, 72, .8)",
        1.0: "rgba(160, 0, 160, .8)"
      }
    }
  };

  function createCanvas() {
    canvas = document.createElement("canvas");
    canvas.id = "pageloader";
    var style = canvas.style;
    style.position = "fixed";
    style.top = style.left = style.right = style.margin = style.padding = 0;
    style.zIndex = 100001;
    style.display = "none";
    document.body.appendChild(canvas);
    window.addEventListener("resize", repaint);
  }

  function repaint() {
    canvas.width = window.innerWidth;
    canvas.height = options.barThickness;
    var ctx = canvas.getContext("2d");

    if (options.barColors.current) {
      var lineGradient = ctx.createLinearGradient(0, 0, canvas.width, 0);

      Object.keys(options.barColors.current).forEach(function(stop) {
        lineGradient.addColorStop(parseFloat(stop), options.barColors.current[stop]);
      });

      ctx.lineWidth = options.barThickness;
      ctx.beginPath();
      ctx.moveTo(0, options.barThickness / 2);
      ctx.lineTo(Math.ceil(currentProgress * canvas.width), options.barThickness / 2);
      ctx.strokeStyle = lineGradient;
      ctx.stroke();
    }
  }

  function addEvent(elem, type, handler) {
    elem.addEventListener(type, handler);
  }

  function progressxConfig(opts) {
    for (var key in opts) {
      if (key === "barColors") {
        for (var colorSet in opts[key]) {options.barColors[colorSet] = opts[key][colorSet];}
      } else {
        if (options.hasOwnProperty(key)) {options[key] = opts[key];}
      }
    }
  }

  function progressxShow(colorSet) {
    if (showing) {return;}
    showing = true;
    if (fadeTimerId !== null) {window.cancelAnimationFrame(fadeTimerId);}
    if (!canvas) {createCanvas();}
    canvas.style.opacity = 1;
    canvas.style.display = "block";
    progressxProgress(0);
    options.barColors.current = options.barColors[colorSet] || options.barColors.default;
    if (options.autoRun) {
      (function loop() {
        progressTimerId = window.requestAnimationFrame(loop);
        progressxProgress("+" + (0.05 * Math.pow(1 - Math.sqrt(currentProgress), 2)));
      })();
    }
  }

  function progressxProgress(to) {
    if (typeof to === "string") {
      to = (to.indexOf("+") >= 0 || to.indexOf("-") >= 0 ? currentProgress : 0) + parseFloat(to);
    }
    currentProgress = to > 1 ? 1 : to;
    repaint();
    return currentProgress;
  }

  function progressxHide() {
    if (!showing) {return;}
    showing = false;
    if (progressTimerId != null) {
      window.cancelAnimationFrame(progressTimerId);
      progressTimerId = null;
    }
    (function loop() {
      if (progressxProgress("+.1") >= 1) {
        canvas.style.opacity -= 0.25;
        if (canvas.style.opacity <= 0.25) {
          canvas.style.display = "none";
          fadeTimerId = null;
          return;
        }
      }
      fadeTimerId = window.requestAnimationFrame(loop);
    })();
  }

  if (typeof module === "object" && typeof module.exports === "object") {
    module.exports = { config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide };
  } else if (typeof define === "function" && define.amd) {
    define(function() {
      return { config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide };
    });
  } else {
    window.progressx = { config: progressxConfig, show: progressxShow, progress: progressxProgress, hide: progressxHide };
  }
})(window, document);