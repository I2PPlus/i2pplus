/*
    Snowflakes © 2024 Denis Seleznev
    Mods by dr|z3d for I2P+
    https://github.com/hcodes/snowflakes/
    MIT License
*/

(function (global, factory) {
  typeof exports === "object" && typeof module !== "undefined" ? (module.exports = factory()) : typeof define === "function" && define.amd ? define(factory) : ((global = typeof globalThis !== "undefined" ? globalThis : global || self), (global.Snowflakes = factory()));
})(this, function () {
  "use strict";

  const themeColors = {
    light: "#87CEEB",
    dark: "#337733",
    classic: "#B0E0E6",
    midnight: "#4169E1"
  };

  const windPatterns = {
    gentle: { duration: "4s", distance: "20px", ease: "ease-in-out" },
    moderate: { duration: "3s", distance: "40px", ease: "ease-in-out" },
    strong: { duration: "2.5s", distance: "60px", ease: "ease-in-out" },
    gusty: { duration: "1.5s", distance: "80px", ease: "cubic-bezier(0.25, 0.46, 0.45, 0.94)" },
    swirling: { duration: "6s", distance: "100px", ease: "cubic-bezier(0.68, -0.55, 0.265, 1.55)" }
  };

  const baseColor = "#5ECDEF";   const colorRegex = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i;

  function varyColor(baseColor, maxVariation) {
    const rgb = colorRegex.exec(baseColor);
    if (!rgb) return baseColor;

    const red = parseInt(rgb[1], 16);
    const green = parseInt(rgb[2], 16);
    const blue = parseInt(rgb[3], 16);

    const clamp = (val) => Math.min(255, Math.max(0, val));

    const newRed = clamp(red + Math.floor(Math.random() * (maxVariation * 2 + 1)) - maxVariation);
    const newGreen = clamp(green + Math.floor(Math.random() * (maxVariation * 2 + 1)) - maxVariation);
    const newBlue = clamp(blue + Math.floor(Math.random() * (maxVariation * 2 + 1)) - maxVariation);

    return "#" + ((1 << 24) + (newRed << 16) + (newGreen << 8) + newBlue).toString(16).slice(1);
  }

  var defaultParams = {
    color: varyColor(baseColor, 30),
    container: document.body,
    count: Math.floor(Math.random() * 11) + 10,
    speed: .3,
    stop: !1,
    rotation: !0,
    minOpacity: .3,
    maxOpacity: .8,
    minSize: 15,
    maxSize: 30,
    types: 6,
    width: undefined,
    height: undefined,
    wind: !0,
    windIntensity: "moderate",
    zIndex: 99999999999,
    autoResize: !0,
  };

  function setStyle(dom, props) {
    const style = dom.style;
    for (const key in props) {
      if (props.hasOwnProperty(key)) {
        style[key] = props[key];
      }
    }
  }

  function showElement(dom) {
    setStyle(dom, {
      display: "block",
    });
  }

  function hideElement(dom) {
    setStyle(dom, {
      display: "none",
    });
  }

  function injectStyle(style, styleNode) {
    if (!styleNode) {
      styleNode = document.createElement("style");
      document.head.appendChild(styleNode);
    }
    styleNode.textContent = style;
    return styleNode;
  }

  function removeNode(node) {
    if (node && node.parentNode) {
      node.parentNode.removeChild(node);
    }
  }

  function isNotEmptyString(value) {
    return typeof value === "string" && value.length > 0;
  }

  function addClass(node, ...classNames) {
    const validClasses = classNames.filter(isNotEmptyString);
    if (validClasses.length) {
      node.classList.add(...validClasses);
    }
  }

  function removeClass(node, ...classNames) {
    const validClasses = classNames.filter(isNotEmptyString);
    if (validClasses.length) {
      node.classList.remove(...validClasses);
    }
  }

  function reflow(node) {
    const display = node.style.display;
    node.style.display = "none";
    node.offsetHeight;
    node.style.display = display;
  }

  function getRandom(from, max) {
    return Math.floor(Math.random() * (max - from) + from);
  }

  function interpolation(x, x1, x2, y1, y2) {
    return y1 + ((y2 - y1) * (x - x1)) / (x2 - x1);
  }
  var maxInnerSize = 20;

  function calcSize(innerSize, minSize, maxSize) {
    return Math.floor(interpolation(innerSize, 0, maxInnerSize, minSize, maxSize));
  }
  var Flake = (function () {
    function Flake(params) {
      var _this = this;
      this.size = 0;
      this.sizeInner = 0;
      this.handleAnimationEnd = function (e) {
        var elem = _this.elem;
        if (!elem) {
          return;
        }
        if (e.target !== elem) {
          return;
        }
        setStyle(elem, {
          left: _this.getLeft(),
        });
        reflow(elem);
      };
      var flake = (this.elem = document.createElement("div"));
      var innerFlake = (this.elemInner = document.createElement("div"));
      this.update(params);
      addClass(flake, "snowflake");
      let windClass = "";
      if (params.wind) {
        const windTypes = Object.keys(windPatterns);
        const randomWind = windTypes[Math.floor(Math.random() * windTypes.length)];
        windClass = `snowflake__inner_wind_${randomWind}`;
      }
      addClass(innerFlake, "snowflake__inner", params.types ? "snowflake__inner_type_" + getRandom(0, params.types) : "", windClass, params.rotation ? "snowflake__inner_rotation" + (Math.random() > 0.5 ? "" : "_reverse") : "");
      flake.appendChild(innerFlake);
      flake.addEventListener("animationend", this.handleAnimationEnd, { passive: true });

      setStyle(flake, { position: "absolute" });

      setStyle(innerFlake, {
        position: "relative",
        width: "100%",
        height: "100%"
      });
    }
    Flake.prototype.getLeft = function () {
      return Math.random() * 99 + "%";
    };
    Flake.prototype.update = function (params) {
      if (!this.elem || !this.elemInner) {
        return;
      }
      var isEqual = params.minSize === params.maxSize;
      this.sizeInner = isEqual ? 0 : getRandom(0, maxInnerSize);
      this.size = calcSize(this.sizeInner, params.minSize, params.maxSize);
      const animationProps = this.getAnimationProps(params);
      const styleProps = {
        position: "absolute",
        animationName: `snowflake_gid_${params.gid}_y`,
        animationDelay: animationProps.animationDelay,
        animationDuration: animationProps.animationDuration,
        left: this.getLeft(),
        top: -Math.sqrt(2) * this.size + "px",
        width: this.size + "px",
        height: this.size + "px",
      };
      if (!isEqual) {
        styleProps.opacity = String(interpolation(this.size, params.minSize, params.maxSize, params.minOpacity, params.maxOpacity));
      }
      setStyle(this.elem, styleProps);
      setStyle(this.elemInner, {
        animationName: `snowflake_gid_${params.gid}_x_${this.sizeInner}`,
        animationDelay: Math.random() * 4 + "s",
      });
    };
    Flake.prototype.resize = function (params) {
      if (!this.elem) {
        return;
      }
      var props = this.getAnimationProps(params);
      setStyle(this.elem, {
        animationDuration: props.animationDuration,
      });
    };
    Flake.prototype.appendTo = function (container) {
      if (!this.elem) {
        return;
      }
      container.appendChild(this.elem);
    };
    Flake.prototype.destroy = function () {
      if (!this.elem) {
        return;
      }
      this.elem.removeEventListener("animationend", this.handleAnimationEnd, { passive: true });
      delete this.elem;
      delete this.elemInner;
    };
    Flake.prototype.getAnimationProps = function (params) {
      const speedMax = params.containerHeight / (50 * params.speed);
      const speedMin = speedMax / 3;
      const duration = interpolation(this.size, params.minSize, params.maxSize, speedMax, speedMin);
      const maxDelay = Math.min(speedMax, 5); // Max 5 seconds delay
      return {
        animationDelay: Math.random() * maxDelay + "s",
        animationDuration: duration + "s",
      };
    };
    return Flake;
  })();
  var mainStyle = null;

  function getScriptPath() {
    var scripts = document.getElementsByTagName('script');
    for (var i = 0; i < scripts.length; i++) {
      if (scripts[i].src && scripts[i].src.indexOf('snowflakes.js') !== -1) {
        return scripts[i].src.substring(0, scripts[i].src.lastIndexOf('/') + 1);
      }
    }
    // Fallback: use router console path
    return '/js/snowflakes/';
  }

  function loadCSS() {
    if (mainStyle !== null) return Promise.resolve(mainStyle);

    var cssUrl = getScriptPath() + 'snowflakes.css';
    return fetch(cssUrl)
      .then(response => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        return response.text();
      })
      .then(cssText => {
        mainStyle = cssText;
        return mainStyle;
      })
      .catch(error => {
        console.warn('Could not load snowflakes.css from', cssUrl, ':', error);
        return '';
      });
  }

  async function getImagesStyle(gid, baseColor) {
    // Read SVG files and replace :color: parameter with actual colors
    const scriptPath = getScriptPath();

    // Function to create colored SVG data URI
    async function createColoredSvg(filename, color) {
      const coloredSvg = await getSvgWithColor(scriptPath + filename + '.svg', color);
      if (coloredSvg) {
        return `url("data:image/svg+xml;charset=utf-8,${encodeURIComponent(coloredSvg)}")`;
      }
      return null;
    }

    const svgPromises = [];
    for (let i = 0; i < 6; i++) {
      svgPromises.push(createColoredSvg(`flake${i + 1}`, varyColor(baseColor, 30)));
    }

    const coloredSvgs = await Promise.all(svgPromises);

    const styleTemplate = `.snowflakes_gid_${gid} .snowflake__inner_type_0:before{background-image:${coloredSvgs[0]};}
.snowflakes_gid_${gid} .snowflake__inner_type_1:before{background-image:${coloredSvgs[1]};}
.snowflakes_gid_${gid} .snowflake__inner_type_2:before{background-image:${coloredSvgs[2]};}
.snowflakes_gid_${gid} .snowflake__inner_type_3:before{background-image:${coloredSvgs[3]};}
.snowflakes_gid_${gid} .snowflake__inner_type_4:before{background-image:${coloredSvgs[4]};}
.snowflakes_gid_${gid} .snowflake__inner_type_5:before{background-image:${coloredSvgs[5]};}`;

    return styleTemplate;
  }

  // Function to fetch SVG and replace color
  async function getSvgWithColor(svgUrl, color) {
    try {
      const response = await fetch(svgUrl);
      const svgText = await response.text();
      return svgText.replace(/:color:/g, color);
    } catch (error) {
      console.warn('Could not load SVG:', svgUrl, error);
      return null;
    }
  }

  var Snowflakes = (function () {
    function Snowflakes(params) {
      var _this = this;
      this.destroyed = !1;
      this.flakes = [];
      this.handleResize = function () {
        if (_this.params.autoResize) {
          _this.resize();
        }
      };
      this.handleOrientationChange = function () {
        _this.resize();
      };
      this.params = this.setParams(params);
      Snowflakes.gid++;
      this.gid = Snowflakes.gid;
      this.container = this.appendContainer();
      if (this.params.stop) {
        this.stop();
      }
      this.appendStyles();
      this.appendFlakes();
      this.containerSize = {
        width: this.width(),
        height: this.height(),
      };
      window.addEventListener("resize", this.handleResize, { passive: true });
      if (screen.orientation && screen.orientation.addEventListener) {
        screen.orientation.addEventListener("change", this.handleOrientationChange, { passive: true });
      }
    }
    Snowflakes.hasSupport = function () {
      return Boolean("onanimationend" in document);
    };
    Snowflakes.prototype.start = function () {
      removeClass(this.container, "snowflakes_paused");
    };
    Snowflakes.prototype.stop = function () {
      addClass(this.container, "snowflakes_paused");
    };
    Snowflakes.prototype.show = function () {
      removeClass(this.container, "snowflakes_hidden");
    };
    Snowflakes.prototype.hide = function () {
      addClass(this.container, "snowflakes_hidden");
    };
    Snowflakes.prototype.resize = function () {
      var newWidth = this.width();
      var newHeight = this.height();
      if (newHeight === this.containerSize.height) {
        return;
      }
      this.containerSize.width = newWidth;
      this.containerSize.height = newHeight;
      var flakeParams = this.getFlakeParams();
      this.flakes.forEach(function (flake) {
        return flake.resize(flakeParams);
      });
      if (this.isBody()) {
        return;
      }
      hideElement(this.container);
      this.updateAnimationStyle();
      showElement(this.container);
    };
    Snowflakes.prototype.destroy = function () {
      if (this.destroyed) {
        return;
      }
      this.destroyed = !0;
      if (Snowflakes.instanceCounter) {
        Snowflakes.instanceCounter--;
      }
      this.removeStyles();
      removeNode(this.container);
      this.flakes.forEach(function (flake) {
        return flake.destroy();
      });
      this.flakes = [];
      window.removeEventListener("resize", this.handleResize, { passive: true });
      if (screen.orientation && screen.orientation.removeEventListener) {
        screen.orientation.removeEventListener("change", this.handleOrientationChange, { passive: true });
      }
    };
    Snowflakes.prototype.isBody = function () {
      return this.params.container === document.body;
    };
    Snowflakes.prototype.appendContainer = function () {
      var container = document.createElement('div');
      addClass(container, 'snowflakes', "snowflakes_gid_".concat(this.gid), this.isBody() ? 'snowflakes_body' : '');
      setStyle(container, {
        zIndex: String(this.params.zIndex),
        position: 'absolute',
        top: '0',
        left: '0',
        width: '100%',
        height: '100%'
      });
      this.params.container.appendChild(container);
      return container
    };
    Snowflakes.prototype.appendStyles = function () {
      var _this = this;
      if (!Snowflakes.instanceCounter) {
        // Load and inject external CSS
        loadCSS().then(function(cssText) {
          if (cssText) {
            _this.mainStyleNode = _this.injectStyle(cssText);
          }
        });
      }
      Snowflakes.instanceCounter++;

      // Load images asynchronously
      var _this = this;
      getImagesStyle(this.gid, this.params.color).then(function(imageStyles) {
        _this.imagesStyleNode = _this.injectStyle(imageStyles);
      }).catch(function(error) {
        console.warn('Could not load image styles:', error);
      });

      this.animationStyleNode = this.injectStyle(this.getAnimationStyle());
    };
    Snowflakes.prototype.injectStyle = function (style, container) {
      if (!style) return container;
      return injectStyle(style.replace(/_gid_value/g, "_gid_".concat(this.gid)), container);
    };
    Snowflakes.prototype.getFlakeParams = function () {
      var height = this.height();
      var params = this.params;
      return {
        containerHeight: height,
        gid: this.gid,
        count: params.count,
        speed: params.speed,
        rotation: params.rotation,
        minOpacity: params.minOpacity,
        maxOpacity: params.maxOpacity,
        minSize: params.minSize,
        maxSize: params.maxSize,
        types: params.types,
        wind: params.wind,
      };
    };
    Snowflakes.prototype.appendFlakes = function () {
      var _this = this;
      var flakeParams = this.getFlakeParams();
      this.flakes = [];
      const fragment = document.createDocumentFragment();

      for (var i = 0; i < this.params.count; i++) {
        var flake = new Flake(flakeParams);
        this.flakes.push(flake);
      }

      this.flakes
        .sort(function (a, b) {
          return a.size - b.size;
        })
        .forEach(function (flake) {
          flake.appendTo(fragment);
        });

      _this.container.appendChild(fragment);
    };
    Snowflakes.prototype.setParams = function (rawParams) {
      var params = rawParams || {};
      var result = {};

      // Define theme colors locally for this function
      const availableThemes = {
        light: "#87CEEB",
        dark: "#337733",
        classic: "#B0E0E6",
        midnight: "#4169E1"
      };

      Object.keys(defaultParams).forEach(function (name) {
        var value = typeof params[name] === "undefined" ? defaultParams[name] : params[name];

        // Handle theme colors
        if (name === 'theme' && availableThemes[value]) {
          result.color = varyColor(availableThemes[value], 30);
        } else {
          result[name] = value;
        }
      });
       // If no color was set but theme is available, use theme color
      if (!result.color && params.theme && availableThemes[params.theme]) {
        result.color = varyColor(availableThemes[params.theme], 30);
      } else if (!result.color) {
        // Use fallback color if no theme or no color set
        result.color = varyColor(baseColor, 30);
      }
       // Don't pass the theme parameter to avoid conflicts
      delete result.theme;
      return result;
    };
    Snowflakes.prototype.getAnimationStyle = function () {
      var fromY = "0px";
      var maxSize = Math.ceil(this.params.maxSize * Math.sqrt(2));
      var toY = this.isBody() ? "calc(100vh + ".concat(maxSize, "px)") : "".concat(this.height() + maxSize, "px");
      var gid = this.gid;
      var cssText = ["@keyframes snowflake_gid_".concat(gid, "_y{from{transform:translateY(").concat(fromY, ")}to{transform:translateY(").concat(toY, ")}}")];
      for (var i = 0; i <= maxInnerSize; i++) {
        var drift = calcSize(i, this.params.minSize, this.params.maxSize);
        var maxDrift = Math.min(drift * 2, 60); // Gentle drift, max 60px for realistic snow movement
        var randomDirection = Math.random() > 0.5 ? 1 : -1; // Random left or right drift
        var driftAmount = (Math.random() * maxDrift * randomDirection) + "px";
        cssText.push("@keyframes snowflake_gid_".concat(gid, "_x_").concat(i, "{0%{transform:translateX(0px)}33%{transform:translateX(").concat(driftAmount, ")}66%{transform:translateX(0px)}100%{transform:translateX(").concat(driftAmount, ")}"));
      }
      return cssText.join("\n");
    };
    Snowflakes.prototype.updateAnimationStyle = function () {
      this.injectStyle(this.getAnimationStyle(), this.animationStyleNode);
    };
    Snowflakes.prototype.removeStyles = function () {
      if (!Snowflakes.instanceCounter) {
        removeNode(this.mainStyleNode);
        delete this.mainStyleNode;
      }
      removeNode(this.imagesStyleNode);
      delete this.imagesStyleNode;
      removeNode(this.animationStyleNode);
      delete this.animationStyleNode;
    };
    Snowflakes.prototype.width = function () {
      return this.params.width || (this.isBody() ? window.innerWidth : this.params.container.offsetWidth);
    };
    Snowflakes.prototype.height = function () {
      return this.params.height || (this.isBody() ? window.innerHeight : this.params.container.offsetHeight + this.params.maxSize);
    };
    Snowflakes.gid = 0;
    Snowflakes.instanceCounter = 0;
    Snowflakes.defaultParams = defaultParams;
    return Snowflakes;
  })();
  return Snowflakes;
});

function initSnowflakes() {
  // Create container element
  const layer = document.createElement("div");
  layer.style.position = "fixed";
  layer.style.top = "0";
  layer.style.left = "0";
  layer.style.width = "100%";
  layer.style.height = "100%";
  layer.style.zIndex = "999999999";
  layer.style.willChange = "transform";
  layer.style.pointerEvents = "none";
  layer.id = "magic";
  document.body.appendChild(layer);

  // Initialize snowflakes with customizable parameters
  // You can pass theme: 'light', 'dark', 'classic', 'midnight'
  // You can pass windIntensity: 'gentle', 'moderate', 'strong', 'gusty', 'swirling'
  const params = {
    container: layer
  };

  // Check for theme in window.theme, then URL parameter, then use default
  const urlParams = new URLSearchParams(window.location.search);
  const theme = window.theme || urlParams.get('theme') || 'light';
  const windIntensity = urlParams.get('wind') || 'moderate';

  // Define theme colors locally
  const availableThemes = {
    light: "#87CEEB",
    dark: "#337733",
    classic: "#B0E0E6",
    midnight: "#4169E1"
  };

  // Define wind patterns locally
  const availableWindPatterns = {
    gentle: "gentle",
    moderate: "moderate",
    strong: "strong",
    gusty: "gusty",
    swirling: "swirling"
  };

  if (theme && availableThemes[theme]) {
    params.theme = theme;
  }
  if (windIntensity && availableWindPatterns[windIntensity]) {
    params.windIntensity = windIntensity;
  }

  new Snowflakes(params);
}

document.addEventListener("DOMContentLoaded", initSnowflakes);