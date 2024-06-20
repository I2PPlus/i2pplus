/**
 * Jdenticon 3.3.0
 * http://jdenticon.com
 *
 * MIT License (https://mit-license.org/)
 *
 * Copyright (c) 2014-2024 Daniel Mester Pirttijärvi
 */

(function (global) {
  'use strict';

  function parseHex(hash, startPosition, octets) {
    return parseInt(hash.substr(startPosition, octets), 16);
  }

  function decToHex(v) {
    v |= 0; // Ensure integer value
    return v < 0 ? "00" : v < 16 ? "0" + v.toString(16) : v < 256 ? v.toString(16) : "ff";
  }

  function hueToRgb(m1, m2, h) {
    h = h < 0 ? h + 6 : h > 6 ? h - 6 : h;
    return decToHex(255 * (h < 1 ? m1 + (m2 - m1) * h : h < 3 ? m2 : h < 4 ? m1 + (m2 - m1) * (4 - h) : m1));
  }

  function parseColor(color) {
    if (/^#[0-9a-f]{3,8}$/i.test(color)) {
      var result;
      var colorLength = color.length;

      if (colorLength < 6) {
        var r = color[1],
          g = color[2],
          b = color[3],
          a = color[4] || "";
        result = "#" + r + r + g + g + b + b + a + a;
      }
      if (colorLength == 7 || colorLength > 8) {
        result = color;
      }
      return result;
    }
  }

  function toCss3Color(hexColor) {
    var a = parseHex(hexColor, 7, 2);
    var result;

    if (isNaN(a)) {
      result = hexColor;
    } else {
      var r = parseHex(hexColor, 1, 2),
        g = parseHex(hexColor, 3, 2),
        b = parseHex(hexColor, 5, 2);
      result = "rgba(" + r + "," + g + "," + b + "," + (a / 255).toFixed(2) + ")";
    }

    return result;
  }

  function hsl(hue, saturation, lightness) {
    var result;

    if (saturation == 0) {
      var partialHex = decToHex(lightness * 255);
      result = partialHex + partialHex + partialHex;
    } else {
      var m2 = lightness <= 0.5 ? lightness * (saturation + 1) : lightness + saturation - lightness * saturation,
        m1 = lightness * 2 - m2;
      result = hueToRgb(m1, m2, hue * 6 + 2) + hueToRgb(m1, m2, hue * 6) + hueToRgb(m1, m2, hue * 6 - 2);
    }

    return "#" + result;
  }

  function correctedHsl(hue, saturation, lightness) {
    var correctors = [0.55, 0.5, 0.5, 0.46, 0.6, 0.55, 0.55],
      corrector = correctors[(hue * 6 + 0.5) | 0];
    lightness = lightness < 0.5 ? lightness * corrector * 2 : corrector + (lightness - 0.5) * (1 - corrector) * 2;
    return hsl(hue, saturation, lightness);
  }

  var CONFIG_PROPERTIES = {
    G /*GLOBAL*/: "jdenticon_config",
    n /*MODULE*/: "config",
  };

  var rootConfigurationHolder = {};

  function defineConfigProperty(rootObject) {
    rootConfigurationHolder = rootObject;
  }

  function configure(newConfiguration) {
    if (arguments.length) {
      rootConfigurationHolder[CONFIG_PROPERTIES.n /*MODULE*/] = newConfiguration;
    }
    return rootConfigurationHolder[CONFIG_PROPERTIES.n /*MODULE*/];
  }

  function getConfiguration(paddingOrLocalConfig, defaultPadding) {
    var configObject = (typeof paddingOrLocalConfig == "object" && paddingOrLocalConfig) || rootConfigurationHolder[CONFIG_PROPERTIES.n /*MODULE*/] || {},
      lightnessConfig = configObject["lightness"] || {},
      saturation = configObject["saturation"] || {},
      colorSaturation = "color" in saturation ? saturation["color"] : saturation,
      grayscaleSaturation = saturation["grayscale"],
      backColor = configObject["backColor"],
      padding = configObject["padding"];

    function lightness(configName, defaultRange) {
      var range = lightnessConfig[configName];
      if (!(range && range.length > 1)) {
        range = defaultRange;
      }

      return function (value) {
        value = range[0] + value * (range[1] - range[0]);
        return value < 0 ? 0 : value > 1 ? 1 : value;
      };
    }

    function hueFunction(originalHue) {
      var hueConfig = configObject["hues"];
      var hue;
      if (hueConfig && hueConfig.length > 0) {
        hue = hueConfig[0 | (0.999 * originalHue * hueConfig.length)];
      }

      return typeof hue == "number"
        ? (((hue / 360) % 1) + 1) % 1
        : originalHue;
    }

    return {
      X /*hue*/: hueFunction,
      p /*colorSaturation*/: typeof colorSaturation == "number" ? colorSaturation : 0.5,
      H /*grayscaleSaturation*/: typeof grayscaleSaturation == "number" ? grayscaleSaturation : 0,
      q /*colorLightness*/: lightness("color", [0.4, 0.8]),
      I /*grayscaleLightness*/: lightness("grayscale", [0.3, 0.9]),
      J /*backColor*/: parseColor(backColor),
      Y /*iconPadding*/: typeof paddingOrLocalConfig == "number" ? paddingOrLocalConfig : typeof padding == "number" ? padding : defaultPadding,
    };
  }

  var ICON_TYPE_SVG = 1;
  var ICON_TYPE_CANVAS = 2;
  var ATTRIBUTES = {
    t /*HASH*/: "data-jdenticon-hash",
    o /*VALUE*/: "data-jdenticon-value",
  };
  var IS_RENDERED_PROPERTY = "jdenticonRendered";
  var ICON_SELECTOR = "[" + ATTRIBUTES.t /*HASH*/ + "],[" + ATTRIBUTES.o /*VALUE*/ + "]";

  function getIdenticonType(el) {
    if (el) {
      var tagName = el["tagName"];

      if (/^svg$/i.test(tagName)) {
        return ICON_TYPE_SVG;
      }

      if (/^canvas$/i.test(tagName) && "getContext" in el) {
        return ICON_TYPE_CANVAS;
      }
    }
  }

  function whenDocumentIsReady(callback) {
    if (typeof document !== "undefined" && typeof window !== "undefined") {
      if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", callback);
        window.addEventListener("load", callback);
      } else {
        callback();
      }
    }
  }

  function observer(updateCallback) {
    if (typeof MutationObserver != "undefined") {
      var mutationObserver = new MutationObserver(function onMutation(mutations) {
        for (var mutationIndex = 0; mutationIndex < mutations.length; mutationIndex++) {
          var mutation = mutations[mutationIndex];
          var addedNodes = mutation.addedNodes;
          for (var addedNodeIndex = 0; addedNodes && addedNodeIndex < addedNodes.length; addedNodeIndex++) {
            var addedNode = addedNodes[addedNodeIndex];
            if (addedNode.nodeType == 1) {
              if (getIdenticonType(addedNode)) {
                updateCallback(addedNode);
              } else {
                var icons = addedNode.querySelectorAll(ICON_SELECTOR);
                for (var iconIndex = 0; iconIndex < icons.length; iconIndex++) {
                  updateCallback(icons[iconIndex]);
                }
              }
            }
          }
          if (mutation.type == "attributes" && getIdenticonType(mutation.target)) {
            updateCallback(mutation.target);
          }
        }
      });

      mutationObserver.observe(document.body, {
        childList: true,
        attributes: true,
        attributeFilter: [ATTRIBUTES.o /*VALUE*/, ATTRIBUTES.t /*HASH*/, "width", "height"],
        subtree: true,
      });
    }
  }

  function Point(x, y) {
    this.x = x;
    this.y = y;
  }

  function Transform(x, y, size, rotation) {
    this.u /*_x*/ = x;
    this.v /*_y*/ = y;
    this.K /*_size*/ = size;
    this.Z /*_rotation*/ = rotation;
  }

  Transform.prototype.L /*transformIconPoint*/ = function (x, y, w, h) {
    var right = this.u /*_x*/ + this.K /*_size*/,
      bottom = this.v /*_y*/ + this.K /*_size*/,
      rotation = this.Z /*_rotation*/;
    return rotation === 1 ? new Point(right - y - (h || 0), this.v /*_y*/ + x) : rotation === 2 ? new Point(right - x - (w || 0), bottom - y - (h || 0)) : rotation === 3 ? new Point(this.u /*_x*/ + y, bottom - x - (w || 0)) : new Point(this.u /*_x*/ + x, this.v /*_y*/ + y);
  };

  var NO_TRANSFORM = new Transform(0, 0, 0, 0);

  function Graphics(renderer) {
    this.M /*_renderer*/ = renderer;
    this.A /*currentTransform*/ = NO_TRANSFORM;
  }

  Graphics.prototype.g /*addPolygon*/ = function (points, invert) {
    var di = invert ? -2 : 2,
      transformedPoints = [];
    for (var i = invert ? points.length - 2 : 0; i < points.length && i >= 0; i += di) {
      transformedPoints.push(this.A /*currentTransform*/.L(/*transformIconPoint*/ points[i], points[i + 1]));
    }
    this.M /*_renderer*/.g(/*addPolygon*/ transformedPoints);
  };

  Graphics.prototype.h /*addCircle*/ = function (x, y, size, invert) {
    var p = this.A /*currentTransform*/.L(/*transformIconPoint*/ x, y, size, size);
    this.M /*_renderer*/.h(/*addCircle*/ p, size, invert);
  };

  Graphics.prototype.i /*addRectangle*/ = function (x, y, w, h, invert) {
    this.g(/*addPolygon*/ [x, y, x + w, y, x + w, y + h, x, y + h], invert);
  };

  Graphics.prototype.j /*addTriangle*/ = function (x, y, w, h, r, invert) {
    var points = [x + w, y, x + w, y + h, x, y + h, x, y];
    points.splice(((r || 0) % 4) * 2, 2);
    this.g(/*addPolygon*/ points, invert);
  };

  Graphics.prototype.N /*addRhombus*/ = function (x, y, w, h, invert) {
    this.g(/*addPolygon*/ [x + w / 2, y, x + w, y + h / 2, x + w / 2, y + h, x, y + h / 2], invert);
  };

  function centerShape(index, g, cell, positionIndex) {
    index = index % 14;

    var k, m, w, h, inner, outer;

    if (!index) {
      k = cell * 0.42;
      g.g(/*addPolygon*/ [0, 0, cell, 0, cell, cell - k * 2, cell - k, cell, 0, cell]);
    } else if (index == 1) {
      w = 0 | (cell * 0.5);
      h = 0 | (cell * 0.8);
      g.j(/*addTriangle*/ cell - w, 0, w, h, 2);
    } else if (index == 2) {
      w = 0 | (cell / 3);
      g.i(/*addRectangle*/ w, w, cell - w, cell - w);
    } else if (index == 3) {
      inner = cell * 0.1;
      outer = cell < 6 ? 1 : cell < 8 ? 2 : 0 | (cell * 0.25);
      inner = inner > 1 ? 0 | inner : inner > 0.5 ? 1 : inner;
      g.i(/*addRectangle*/ outer, outer, cell - inner - outer, cell - inner - outer);
    } else if (index == 4) {
      m = 0 | (cell * 0.15);
      w = 0 | (cell * 0.5);
      g.h(/*addCircle*/ cell - w - m, cell - w - m, w);
    } else if (index == 5) {
      inner = cell * 0.1;
      outer = inner * 4;
      outer > 3 && (outer = 0 | outer);
      g.i(/*addRectangle*/ 0, 0, cell, cell);
      g.g(/*addPolygon*/ [outer, outer, cell - inner, outer, outer + (cell - outer - inner) / 2, cell - inner], true);
    } else if (index == 6) {
      g.g(/*addPolygon*/ [0, 0, cell, 0, cell, cell * 0.7, cell * 0.4, cell * 0.4, cell * 0.7, cell, 0, cell]);
    } else if (index == 7) {
      g.j(/*addTriangle*/ cell / 2, cell / 2, cell / 2, cell / 2, 3);
    } else if (index == 8) {
      g.i(/*addRectangle*/ 0, 0, cell, cell / 2);
      g.i(/*addRectangle*/ 0, cell / 2, cell / 2, cell / 2);
      g.j(/*addTriangle*/ cell / 2, cell / 2, cell / 2, cell / 2, 1);
    } else if (index == 9) {
      inner = cell * 0.14;
      outer = cell < 4 ? 1 : cell < 6 ? 2 : 0 | (cell * 0.35);
      inner = cell < 8 ? inner : 0 | inner;
      g.i(/*addRectangle*/ 0, 0, cell, cell);
      g.i(/*addRectangle*/ outer, outer, cell - outer - inner, cell - outer - inner, true);
    } else if (index == 10) {
      inner = cell * 0.12;
      outer = inner * 3;
      g.i(/*addRectangle*/ 0, 0, cell, cell);
      g.h(/*addCircle*/ outer, outer, cell - inner - outer, true);
    } else if (index == 11) {
      g.j(/*addTriangle*/ cell / 2, cell / 2, cell / 2, cell / 2, 3);
    } else if (index == 12) {
      m = cell * 0.25;
      g.i(/*addRectangle*/ 0, 0, cell, cell);
      g.N(/*addRhombus*/ m, m, cell - m, cell - m, true);
    } else if (!positionIndex) {
      m = cell * 0.4;
      w = cell * 1.2;
      g.h(/*addCircle*/ m, m, w);
    }
  }

  function outerShape(index, g, cell) {
    index = index % 4;
    var m;

    if (!index) {
      g.j(/*addTriangle*/ 0, 0, cell, cell, 0);
    } else if (index == 1) {
      g.j(/*addTriangle*/ 0, cell / 2, cell, cell / 2, 0);
    } else if (index == 2) {
      g.N(/*addRhombus*/ 0, 0, cell, cell);
    } else {
      m = cell / 6;
      g.h(/*addCircle*/ m, m, cell - 2 * m);
    }
  }

  function colorTheme(hue, config) {
    hue = config.X(/*hue*/ hue);
    return [
      correctedHsl(hue, config.H /*grayscaleSaturation*/, config.I(/*grayscaleLightness*/ 0)),
      correctedHsl(hue, config.p /*colorSaturation*/, config.q(/*colorLightness*/ 0.5)),
      correctedHsl(hue, config.H /*grayscaleSaturation*/, config.I(/*grayscaleLightness*/ 1)),
      correctedHsl(hue, config.p /*colorSaturation*/, config.q(/*colorLightness*/ 1)),
      correctedHsl(hue, config.p /*colorSaturation*/, config.q(/*colorLightness*/ 0)),
    ];
  }

  function iconGenerator(renderer, hash, config) {
    var parsedConfig = getConfiguration(config, 0.08);
    var size = renderer.k /*iconSize*/;
    var padding = (0.5 + size * parsedConfig.Y /*iconPadding*/) | 0;
    size -= padding * 2;
    var graphics = new Graphics(renderer);
    var cell = 0 | (size / 4);
    var x = 0 | (padding + size / 2 - cell * 2);
    var y = 0 | (padding + size / 2 - cell * 2);

    function renderShape(colorIndex, shapes, index, rotationIndex, positions) {
      var shapeIndex = parseHex(hash, index, 1);
      var r = rotationIndex ? parseHex(hash, rotationIndex, 1) : 0;
      renderer.O(/*beginShape*/ availableColors[selectedColorIndexes[colorIndex]]);
      for (var i = 0; i < positions.length; i++) {
        graphics.A /*currentTransform*/ = new Transform(x + positions[i][0] * cell, y + positions[i][1] * cell, cell, r++ % 4);
        shapes(shapeIndex, graphics, cell, i);
      }
      renderer.P /*endShape*/();
    }

    var hue = parseHex(hash, -7) / 0xfffffff;
    var availableColors = colorTheme(hue, parsedConfig);
    var selectedColorIndexes = [];

    for (var i = 0; i < 3; i++) {
      index = parseHex(hash, 8 + i, 1) % availableColors.length;
      if (
        isDuplicate([0, 4]) || // Disallow dark gray and dark color combo
        isDuplicate([2, 3])
      ) {
        index = 1;
      }
      selectedColorIndexes.push(index);
    }

    var index;

    function isDuplicate(values) {
      if (values.indexOf(index) >= 0) {
        for (var i = 0; i < values.length; i++) {
          if (selectedColorIndexes.indexOf(values[i]) >= 0) {
            return true;
          }
        }
      }
    }

    renderShape(0, outerShape, 2, 3, [
      [1, 0],
      [2, 0],
      [2, 3],
      [1, 3],
      [0, 1],
      [3, 1],
      [3, 2],
      [0, 2],
    ]);
    renderShape(1, outerShape, 4, 5, [
      [0, 0],
      [3, 0],
      [3, 3],
      [0, 3],
    ]);
    renderShape(2, centerShape, 1, null, [
      [1, 1],
      [2, 1],
      [2, 2],
      [1, 2],
    ]);
    renderer.finish();
  }

  function sha1(message) {
    var HASH_SIZE_HALF_BYTES = 40;
    var BLOCK_SIZE_WORDS = 16;

    var i = 0,
      f = 0,
      data = [],
      dataSize,
      hashBuffer = [],
      a = 0x67452301,
      b = 0xefcdab89,
      c = ~a,
      d = ~b,
      e = 0xc3d2e1f0,
      hash = [a, b, c, d, e],
      blockStartIndex = 0,
      hexHash = "";

    function rotl(value, shift) {
      return (value << shift) | (value >>> (32 - shift));
    }

    for (; i < message.length; f++) {
      data[f >> 2] =
        data[f >> 2] |
        ((message[i] == "%"
          ? parseInt(message.substring(i + 1, (i += 3)), 16)
          : message.charCodeAt(i++)) <<
          ((3 - (f & 3)) * 8));
    }

    dataSize = (((f + 7) >> 6) + 1) * BLOCK_SIZE_WORDS;
    data[dataSize - 1] = f * 8 - 8;

    for (; blockStartIndex < dataSize; blockStartIndex += BLOCK_SIZE_WORDS) {
      for (i = 0; i < 80; i++) {
        f =
          rotl(a, 5) +
          e +
          (i < 20
            ? ((b & c) ^ (~b & d)) + 0x5a827999
            : i < 40
            ? (b ^ c ^ d) + 0x6ed9eba1
            : i < 60
            ? ((b & c) ^ (b & d) ^ (c & d)) + 0x8f1bbcdc
            : (b ^ c ^ d) + 0xca62c1d6) +
          (hashBuffer[i] =
            i < BLOCK_SIZE_WORDS
              ? data[blockStartIndex + i] | 0
              : rotl(hashBuffer[i - 3] ^ hashBuffer[i - 8] ^ hashBuffer[i - 14] ^ hashBuffer[i - 16], 1));

        e = d;
        d = c;
        c = rotl(b, 30);
        b = a;
        a = f;
      }

      hash[0] = a = (hash[0] + a) | 0;
      hash[1] = b = (hash[1] + b) | 0;
      hash[2] = c = (hash[2] + c) | 0;
      hash[3] = d = (hash[3] + d) | 0;
      hash[4] = e = (hash[4] + e) | 0;
    }

    for (i = 0; i < HASH_SIZE_HALF_BYTES; i++) {
      hexHash += (
        (hash[i >> 3] >>>
          ((7 - (i & 7)) * 4)) & // Clamp to half-byte
        0xf
      ).toString(16);
    }

    return hexHash;
  }

  function isValidHash(hashCandidate) {
    return /^[0-9a-f]{11,}$/i.test(hashCandidate) && hashCandidate;
  }

  function computeHash(value) {
    return sha1(value == null ? "" : "" + value);
  }

  function CanvasRenderer(ctx, iconSize) {
    var canvas = ctx.canvas;
    var width = canvas.width;
    var height = canvas.height;
    ctx.save();
    if (!iconSize) {
      iconSize = Math.min(width, height);
      ctx.translate(((width - iconSize) / 2) | 0, ((height - iconSize) / 2) | 0);
    }

    this.l /*_ctx*/ = ctx;
    this.k /*iconSize*/ = iconSize;
    ctx.clearRect(0, 0, iconSize, iconSize);
  }

  CanvasRenderer.prototype.m /*setBackground*/ = function (fillColor) {
    var ctx = this.l /*_ctx*/;
    var iconSize = this.k /*iconSize*/;

    ctx.fillStyle = toCss3Color(fillColor);
    ctx.fillRect(0, 0, iconSize, iconSize);
  };

  CanvasRenderer.prototype.O /*beginShape*/ = function (fillColor) {
    var ctx = this.l /*_ctx*/;
    ctx.fillStyle = toCss3Color(fillColor);
    ctx.beginPath();
  };

  CanvasRenderer.prototype.P /*endShape*/ = function () {
    this.l /*_ctx*/
      .fill();
  };

  CanvasRenderer.prototype.g /*addPolygon*/ = function (points) {
    var ctx = this.l /*_ctx*/;
    ctx.moveTo(points[0].x, points[0].y);
    for (var i = 1; i < points.length; i++) {
      ctx.lineTo(points[i].x, points[i].y);
    }
    ctx.closePath();
  };

  CanvasRenderer.prototype.h /*addCircle*/ = function (point, diameter, counterClockwise) {
    var ctx = this.l /*_ctx*/,
      radius = diameter / 2;
    ctx.moveTo(point.x + radius, point.y + radius);
    ctx.arc(point.x + radius, point.y + radius, radius, 0, Math.PI * 2, counterClockwise);
    ctx.closePath();
  };

  CanvasRenderer.prototype.finish = function () {
    this.l /*_ctx*/
      .restore();
  };

  function drawIcon(ctx, hashOrValue, size, config) {
    if (!ctx) {
      throw new Error("No canvas specified.");
    }
    iconGenerator(new CanvasRenderer(ctx, size), isValidHash(hashOrValue) || computeHash(hashOrValue), config);

    var canvas = ctx.canvas;
    if (canvas) {
      canvas[IS_RENDERED_PROPERTY] = true;
    }
  }

  function svgValue(value) {
    return ((value * 10 + 0.5) | 0) / 10;
  }

  function SvgPath() {
    this.B /*dataString*/ = "";
  }

  SvgPath.prototype.g /*addPolygon*/ = function (points) {
    var dataString = "";
    for (var i = 0; i < points.length; i++) {
      dataString += (i ? "L" : "M") + svgValue(points[i].x) + " " + svgValue(points[i].y);
    }
    this.B /*dataString*/ += dataString + "Z";
  };

  SvgPath.prototype.h /*addCircle*/ = function (point, diameter, counterClockwise) {
    var sweepFlag = counterClockwise ? 0 : 1,
      svgRadius = svgValue(diameter / 2),
      svgDiameter = svgValue(diameter),
      svgArc = "a" + svgRadius + "," + svgRadius + " 0 1," + sweepFlag + " ";
    this.B /*dataString*/ += "M" + svgValue(point.x) + " " + svgValue(point.y + diameter / 2) + svgArc + svgDiameter + ",0" + svgArc + -svgDiameter + ",0";
  };

  function SvgRenderer(target) {
    this.C /*_path*/;
    this.D /*_pathsByColor*/ = {};
    this.R /*_target*/ = target;
    this.k /*iconSize*/ = target.k /*iconSize*/;
  }

  SvgRenderer.prototype.m /*setBackground*/ = function (fillColor) {
    var match = /^(#......)(..)?/.exec(fillColor),
      opacity = match[2] ? parseHex(match[2], 0) / 255 : 1;
    this.R /*_target*/.m(/*setBackground*/ match[1], opacity);
  };

  SvgRenderer.prototype.O /*beginShape*/ = function (color) {
    this.C /*_path*/ = this.D /*_pathsByColor*/[color] || (this.D /*_pathsByColor*/[color] = new SvgPath());
  };

  SvgRenderer.prototype.P /*endShape*/ = function () {};

  SvgRenderer.prototype.g /*addPolygon*/ = function (points) {
    this.C /*_path*/.g(/*addPolygon*/ points);
  };

  SvgRenderer.prototype.h /*addCircle*/ = function (point, diameter, counterClockwise) {
    this.C /*_path*/.h(/*addCircle*/ point, diameter, counterClockwise);
  };

  SvgRenderer.prototype.finish = function () {
    var this$1 = this;
    var pathsByColor = this.D /*_pathsByColor*/;
    for (var color in pathsByColor) {
      if (pathsByColor.hasOwnProperty(color)) {
        this$1.R /*_target*/.S(/*appendPath*/ color, pathsByColor[color].B /*dataString*/);
      }
    }
  };

  var SVG_CONSTANTS = {
    T /*XMLNS*/: "http://www.w3.org/2000/svg",
    U /*WIDTH*/: "width",
    V /*HEIGHT*/: "height",
  };

  function SvgWriter(iconSize) {
    this.k /*iconSize*/ = iconSize;
    this.F /*_s*/ = '<svg xmlns="' + SVG_CONSTANTS.T /*XMLNS*/ + '" width="' + iconSize + '" height="' + iconSize + '" viewBox="0 0 ' + iconSize + " " + iconSize + '">';
  }

  SvgWriter.prototype.m /*setBackground*/ = function (fillColor, opacity) {
    if (opacity) {
      this.F /*_s*/ += '<rect width="100%" height="100%" fill="' + fillColor + '" opacity="' + opacity.toFixed(2) + '"/>';
    }
  };

  SvgWriter.prototype.S /*appendPath*/ = function (color, dataString) {
    this.F /*_s*/ += '<path fill="' + color + '" d="' + dataString + '"/>';
  };

  SvgWriter.prototype.toString = function () {
    return this.F /*_s*/ + "</svg>";
  };

  function toSvg(hashOrValue, size, config) {
    var writer = new SvgWriter(size);
    iconGenerator(new SvgRenderer(writer), isValidHash(hashOrValue) || computeHash(hashOrValue), config);
    return writer.toString();
  }

  function SvgElement_append(parentNode, name) {
    var keyValuePairs = [],
      len = arguments.length - 2;
    while (len-- > 0) keyValuePairs[len] = arguments[len + 2];

    var el = document.createElementNS(SVG_CONSTANTS.T /*XMLNS*/, name);
    for (var i = 0; i + 1 < keyValuePairs.length; i += 2) {
      el.setAttribute(keyValuePairs[i], keyValuePairs[i + 1]);
    }

    parentNode.appendChild(el);
  }

  function SvgElement(element) {
    var iconSize = (this.k /*iconSize*/ = Math.min(Number(element.getAttribute(SVG_CONSTANTS.U /*WIDTH*/)) || 100, Number(element.getAttribute(SVG_CONSTANTS.V /*HEIGHT*/)) || 100));
    this.W /*_el*/ = element;
    while (element.firstChild) {
      element.removeChild(element.firstChild);
    }
    element.setAttribute("viewBox", "0 0 " + iconSize + " " + iconSize);
    element.setAttribute("preserveAspectRatio", "xMidYMid meet");
  }

  SvgElement.prototype.m /*setBackground*/ = function (fillColor, opacity) {
    if (opacity) {
      SvgElement_append(this.W /*_el*/, "rect", SVG_CONSTANTS.U /*WIDTH*/, "100%", SVG_CONSTANTS.V /*HEIGHT*/, "100%", "fill", fillColor, "opacity", opacity);
    }
  };

  SvgElement.prototype.S /*appendPath*/ = function (color, dataString) {
    SvgElement_append(this.W /*_el*/, "path", "fill", color, "d", dataString);
  };

  function updateAll() {
    if (document.querySelectorAll) {
      update(ICON_SELECTOR);
    }
  }

  function updateAllConditional() {
    var elements = document.querySelectorAll(ICON_SELECTOR);
    for (var i = 0; i < elements.length; i++) {
      var el = elements[i];
      if (!el[IS_RENDERED_PROPERTY]) {
        update(el);
      }
    }
  }

  function update(el, hashOrValue, config) {
    renderDomElement(el, hashOrValue, config, function (el, iconType) {
      if (iconType) {
        return iconType == ICON_TYPE_SVG ? new SvgRenderer(new SvgElement(el)) : new CanvasRenderer(/** @type {HTMLCanvasElement} */ (el).getContext("2d"));
      }
    });
  }

  function renderDomElement(el, hashOrValue, config, rendererFactory) {
    if (typeof el === "string") {
      var elements = document.querySelectorAll(el);
      for (var i = 0; i < elements.length; i++) {
        renderDomElement(elements[i], hashOrValue, config, rendererFactory);
      }
      return;
    }

    var hash = isValidHash(hashOrValue) || (hashOrValue != null && computeHash(hashOrValue)) || isValidHash(el.getAttribute(ATTRIBUTES.t /*HASH*/)) || (el.hasAttribute(ATTRIBUTES.o /*VALUE*/) && computeHash(el.getAttribute(ATTRIBUTES.o /*VALUE*/)));
    if (!hash) {
      return;
    }
    var renderer = rendererFactory(el, getIdenticonType(el));
    if (renderer) {
      iconGenerator(renderer, hash, config);
      el[IS_RENDERED_PROPERTY] = true;
    }
  }

  var jdenticon = updateAll;

  defineConfigProperty(jdenticon);

  jdenticon.configure = configure;
  jdenticon.drawIcon = drawIcon;
  jdenticon.toSvg = toSvg;
  jdenticon.update = update;
  jdenticon.updateCanvas = update;
  jdenticon.updateSvg = update;

  jdenticon.version = "3.3.0";
  jdenticon.bundle = "browser-umd";

  function jdenticonStartup() {
    var replaceMode = (jdenticon[CONFIG_PROPERTIES.n /*MODULE*/] || global[CONFIG_PROPERTIES.G /*GLOBAL*/] || {})["replaceMode"];
    if (replaceMode != "never") {
      updateAllConditional();
      if (replaceMode == "observe") {
        observer(update);
      }
    }
  }

  whenDocumentIsReady(jdenticonStartup);
})(typeof self !== "undefined" ? self : this);