/* I2P+ geomap.js by dr|z3d */
/* Heavily modified from https://cartosvg.com/mercator */

/**
 * @module geomap
 * @description Interactive SVG world map for the NetDB page showing router distribution
 * by country, floodfill, or bandwidth tier. Supports hover infoboxes, color-coded
 * shape classes based on router counts, and periodic data refresh from /netdb.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function () {
  'use strict';

  // ============ CONFIGURATION ============
  const CONFIG = {
    STORE_INTERVAL: 15_000,    // Refresh router counts every 15s
    DEBOUNCE_DELAY: 30,        // UI event debounce (ms)
    FETCH_TIMEOUT: 15_000,     // Retry delay on error (ms)
    MAX_RETRY_DELAY: 120_000,  // Max exponential backoff cap (ms)
    RETRY_MULTIPLIER: 1.5,     // Exponential backoff factor
    MAP_WIDTH: 720,
    MAP_HEIGHT: 475,
  };

  // ============ DOM REFERENCES ============
  const geomap = document.querySelector("#geomap");
  const infobox = document.querySelector("#netdbmap #info");

  // ============ STATE ============
  let debugging = false;
  let verbose = false;
  let currentRouterClass = getQueryParameter("class") || "countries";
  let routerCounts = JSON.parse(localStorage.getItem("routerCounts")) || {};
  let retryAttempt = 0;
  let fetchIntervalId = null;

  // ============ CACHED DATA ============
  // Pre-compute count threshold classes for efficient removal
  const COUNT_CLASSES = new Set([
    'count_1', 'count_10', 'count_50', 'count_100',
    'count_200', 'count_300', 'count_400', 'count_500'
  ]);

  // Thresholds in descending order for efficient lookup (no reverse() needed)
  const COUNT_THRESHOLDS = [
    { threshold: 500, className: "count_500" },
    { threshold: 400, className: "count_400" },
    { threshold: 300, className: "count_300" },
    { threshold: 200, className: "count_200" },
    { threshold: 100, className: "count_100" },
    { threshold: 50, className: "count_50" },
    { threshold: 10, className: "count_10" },
    { threshold: 1, className: "count_1" },
  ];

  // WeakMap for private element state (avoids DOM property pollution)
  const elementState = new WeakMap();

  // ============ MAP DATA ============
  const map = {
    data: {
      countries: {
        Afghanistan: { region: "Asia", code: "af" },
        Albania: { region: "Europe", code: "al" },
        Algeria: { region: "Africa", code: "dz" },
        Andorra: { region: "Europe", code: "ad" },
        Angola: { region: "Africa", code: "ao" },
        "Antigua and Barbuda": { region: "Americas", code: "ag" },
        Argentina: { region: "Americas", code: "ar" },
        Armenia: { region: "Asia", code: "am" },
        Australia: { region: "Oceania", code: "au" },
        Austria: { region: "Europe", code: "at" },
        Azerbaijan: { region: "Asia", code: "az" },
        Bahamas: { region: "Americas", code: "bs" },
        Bahrain: { region: "Asia", code: "bh" },
        Bangladesh: { region: "Asia", code: "bd" },
        Barbados: { region: "Americas", code: "bb" },
        Belarus: { region: "Europe", code: "by" },
        Belgium: { region: "Europe", code: "be" },
        Belize: { region: "Americas", code: "bz" },
        Benin: { region: "Africa", code: "bj" },
        Bhutan: { region: "Asia", code: "bt" },
        Bolivia: { region: "Americas", code: "bo" },
        "Bosnia and Herzegovina": { region: "Europe", code: "ba" },
        Botswana: { region: "Africa", code: "bw" },
        Brazil: { region: "Americas", code: "br" },
        "Brunei Darussalam": { region: "Asia", code: "bn" },
        Bulgaria: { region: "Europe", code: "bg" },
        "Burkina Faso": { region: "Africa", code: "bf" },
        Burundi: { region: "Africa", code: "bi" },
        "Cabo Verde": { region: "Africa", code: "cv" },
        Cambodia: { region: "Asia", code: "kh" },
        Cameroon: { region: "Africa", code: "cm" },
        Canada: { region: "Americas", code: "ca" },
        "Central African Republic": { region: "Africa", code: "cf" },
        Chad: { region: "Africa", code: "td" },
        Chile: { region: "Americas", code: "cl" },
        China: { region: "Asia", code: "cn" },
        Colombia: { region: "Americas", code: "co" },
        Comoros: { region: "Africa", code: "km" },
        "Congo (Democratic Republic)": { region: "Africa", code: "cd" },
        Congo: { region: "Africa", code: "cg" },
        "Costa Rica": { region: "Americas", code: "cr" },
        "Côte d'Ivoire": { region: "Africa", code: "ci" },
        Croatia: { region: "Europe", code: "hr" },
        Cuba: { region: "Americas", code: "cu" },
        Cyprus: { region: "Asia", code: "cy" },
        Czechia: { region: "Europe", code: "cz" },
        Denmark: { region: "Europe", code: "dk" },
        Djibouti: { region: "Africa", code: "dj" },
        "Dominican Republic": { region: "Americas", code: "do" },
        Dominica: { region: "Americas", code: "dm" },
        Ecuador: { region: "Americas", code: "ec" },
        Egypt: { region: "Africa", code: "eg" },
        "El Salvador": { region: "Americas", code: "sv" },
        "Equatorial Guinea": { region: "Africa", code: "gq" },
        Eritrea: { region: "Africa", code: "er" },
        Estonia: { region: "Europe", code: "ee" },
        Eswatini: { region: "Africa", code: "sz" },
        Ethiopia: { region: "Africa", code: "et" },
        "Falkland Islands": { region: "Americas", code: "fk" },
        Fiji: { region: "Oceania", code: "fj" },
        Finland: { region: "Europe", code: "fi" },
        France: { region: "Europe", code: "fr" },
        Gabon: { region: "Africa", code: "ga" },
        Gambia: { region: "Africa", code: "gm" },
        Georgia: { region: "Asia", code: "ge" },
        Germany: { region: "Europe", code: "de" },
        Ghana: { region: "Africa", code: "gh" },
        Greece: { region: "Europe", code: "gr" },
        Greenland: { region: "Americas", code: "gl" },
        Grenada: { region: "Americas", code: "gd" },
        Guatemala: { region: "Americas", code: "gt" },
        "Guinea-Bissau": { region: "Africa", code: "gw" },
        Guinea: { region: "Africa", code: "gn" },
        Guyana: { region: "Americas", code: "gy" },
        Haiti: { region: "Americas", code: "ht" },
        Honduras: { region: "Americas", code: "hn" },
        Hungary: { region: "Europe", code: "hu" },
        Iceland: { region: "Europe", code: "is" },
        India: { region: "Asia", code: "in" },
        Indonesia: { region: "Asia", code: "id" },
        Iran: { region: "Asia", code: "ir" },
        Iraq: { region: "Asia", code: "iq" },
        Ireland: { region: "Europe", code: "ie" },
        Israel: { region: "Asia", code: "il" },
        Italy: { region: "Europe", code: "it" },
        Jamaica: { region: "Americas", code: "jm" },
        Japan: { region: "Asia", code: "jp" },
        Jordan: { region: "Asia", code: "jo" },
        Kazakhstan: { region: "Asia", code: "kz" },
        Kenya: { region: "Africa", code: "ke" },
        Kiribati: { region: "Oceania", code: "ki" },
        "North Korea": { region: "Asia", code: "kp" },
        "South Korea": { region: "Asia", code: "kr" },
        Kuwait: { region: "Asia", code: "kw" },
        Kyrgyzstan: { region: "Asia", code: "kg" },
        "Lao People's Democratic Republic": { region: "Asia", code: "la" },
        Latvia: { region: "Europe", code: "lv" },
        Lebanon: { region: "Asia", code: "lb" },
        Lesotho: { region: "Africa", code: "ls" },
        Liberia: { region: "Africa", code: "lr" },
        Libya: { region: "Africa", code: "ly" },
        Liechtenstein: { region: "Europe", code: "li" },
        Lithuania: { region: "Europe", code: "lt" },
        Luxembourg: { region: "Europe", code: "lu" },
        Madagascar: { region: "Africa", code: "mg" },
        Malawi: { region: "Africa", code: "mw" },
        Malaysia: { region: "Asia", code: "my" },
        Mali: { region: "Africa", code: "ml" },
        Malta: { region: "Europe", code: "mt" },
        Mauritania: { region: "Africa", code: "mr" },
        Mauritius: { region: "Africa", code: "mu" },
        Mexico: { region: "Americas", code: "mx" },
        "Micronesia (Federated States of)": { region: "Oceania", code: "fm" },
        Moldova: { region: "Europe", code: "md" },
        Mongolia: { region: "Asia", code: "mn" },
        Montenegro: { region: "Europe", code: "me" },
        Morocco: { region: "Africa", code: "ma" },
        Mozambique: { region: "Africa", code: "mz" },
        Myanmar: { region: "Asia", code: "mm" },
        Namibia: { region: "Africa", code: "na" },
        Nauru: { region: "Oceania", code: "nr" },
        Nepal: { region: "Asia", code: "np" },
        Netherlands: { region: "Europe", code: "nl" },
        "New Zealand": { region: "Oceania", code: "nz" },
        Nicaragua: { region: "Americas", code: "ni" },
        Nigeria: { region: "Africa", code: "ng" },
        Niger: { region: "Africa", code: "ne" },
        "North Macedonia": { region: "Europe", code: "mk" },
        Norway: { region: "Europe", code: "no" },
        Oman: { region: "Asia", code: "om" },
        Pakistan: { region: "Asia", code: "pk" },
        Palau: { region: "Oceania", code: "pw" },
        Panama: { region: "Americas", code: "pa" },
        "Papua New Guinea": { region: "Oceania", code: "pg" },
        Paraguay: { region: "Americas", code: "py" },
        Peru: { region: "Americas", code: "pe" },
        Philippines: { region: "Asia", code: "ph" },
        Poland: { region: "Europe", code: "pl" },
        Portugal: { region: "Europe", code: "pt" },
        Qatar: { region: "Asia", code: "qa" },
        Romania: { region: "Europe", code: "ro" },
        Russia: { region: "Europe", code: "ru" },
        Rwanda: { region: "Africa", code: "rw" },
        "Saint Kitts and Nevis": { region: "Americas", code: "kn" },
        "Saint Lucia": { region: "Americas", code: "lc" },
        "Saint Vincent and the Grenadines": { region: "Americas", code: "vc" },
        Samoa: { region: "Oceania", code: "ws" },
        "San Marino": { region: "Europe", code: "sm" },
        "Sao Tome and Principe": { region: "Africa", code: "st" },
        "Saudi Arabia": { region: "Asia", code: "sa" },
        Senegal: { region: "Africa", code: "sn" },
        Serbia: { region: "Europe", code: "rs" },
        Seychelles: { region: "Africa", code: "sc" },
        "Sierra Leone": { region: "Africa", code: "sl" },
        Singapore: { region: "Asia", code: "sg" },
        Slovakia: { region: "Europe", code: "sk" },
        Slovenia: { region: "Europe", code: "si" },
        "Solomon Islands": { region: "Oceania", code: "sb" },
        Somalia: { region: "Africa", code: "so" },
        "South Africa": { region: "Africa", code: "za" },
        "South Sudan": { region: "Africa", code: "ss" },
        Spain: { region: "Europe", code: "es" },
        "Sri Lanka": { region: "Asia", code: "lk" },
        Sudan: { region: "Africa", code: "sd" },
        Suriname: { region: "Americas", code: "sr" },
        Sweden: { region: "Europe", code: "se" },
        Switzerland: { region: "Europe", code: "ch" },
        Syria: { region: "Asia", code: "sy" },
        Taiwan: { region: "Asia", code: "tw" },
        Tajikistan: { region: "Asia", code: "tj" },
        Tanzania: { region: "Africa", code: "tz" },
        Thailand: { region: "Asia", code: "th" },
        Tibet: { region: "Asia", code: "cn" },
        "Timor-Leste": { region: "Asia", code: "tl" },
        Togo: { region: "Africa", code: "tg" },
        Tonga: { region: "Oceania", code: "to" },
        "Trinidad and Tobago": { region: "Americas", code: "tt" },
        Tunisia: { region: "Africa", code: "tn" },
        Turkey: { region: "Asia", code: "tr" },
        Turkmenistan: { region: "Asia", code: "tm" },
        Uganda: { region: "Africa", code: "ug" },
        Ukraine: { region: "Europe", code: "ua" },
        "United Arab Emirates": { region: "Asia", code: "ae" },
        "United Kingdom": { region: "Europe", code: "gb" },
        "United States of America": { region: "Americas", code: "us" },
        Uruguay: { region: "Americas", code: "uy" },
        Uzbekistan: { region: "Asia", code: "uz" },
        Vanuatu: { region: "Oceania", code: "vu" },
        Venezuela: { region: "Americas", code: "ve" },
        "Viet Nam": { region: "Asia", code: "vn" },
        "Western Sahara": { region: "Africa", code: "eh" },
        Yemen: { region: "Asia", code: "ye" },
        Zambia: { region: "Africa", code: "zm" },
        Zimbabwe: { region: "Africa", code: "zw" },
      },
    },
    infoboxHTML: {
      countries: '<span><b>Country: </b> <img class=mapflag width=9 height=6 src="/flags.jsp?c=${data.code}"> ${shapeId} (${data.code})</span><br>' +
        "<span><b>Region: </b>${data.region}</span><br>\n" + "<span><b>Routers: 0</b></span>\n",
    },
  };

  // ============ UTILITIES ============

  /**
   * Retrieves a URL query parameter by name.
   * @function getQueryParameter
   * @param {string} name - The query parameter name
   * @returns {string|null} The parameter value, or null if not present
   */
  function getQueryParameter(name) {
    return new URLSearchParams(window.location.search).get(name);
  }

  // Template rendering helper - cleaner than chained replace()
  /**
   * Renders a template string with variable substitution.
   * @function renderTemplate
   * @param {string} template - The template string with ${variable} placeholders
   * @param {Object} context - The context object containing variable values
   * @returns {string} The rendered template
   */
  function renderTemplate(template, context) {
    return template.replace(/\$\{([^}]+)\}/g, (_, path) => {
      return path.split('.').reduce((obj, key) => obj?.[key], context);
    });
  }

  // Debounce with proper return value handling
  /**
   * Creates a debounced version of the given function.
   * @function debounce
   * @param {Function} func - The function to debounce
   * @param {number} wait - The debounce delay in milliseconds
   * @param {boolean} [immediate=false] - Whether to call on the leading edge
   * @returns {Function} The debounced function
   */
  function debounce(func, wait, immediate = false) {
    let timeout;
    return function (...args) {
      const context = this;
      const callNow = immediate && !timeout;
      clearTimeout(timeout);
      timeout = setTimeout(() => {
        timeout = null;
        if (!immediate) func.apply(context, args);
      }, wait);
      if (callNow) func.apply(context, args);
    };
  }

  // Exponential backoff scheduler for retry logic
  /**
   * Schedules a retry with exponential backoff.
   * @function scheduleRetry
   * @param {Function} callback - The function to retry
   * @param {number} [attempt=0] - The current attempt number
   * @returns {void}
   */
  function scheduleRetry(callback, attempt = 0) {
    const delay = Math.min(
      CONFIG.FETCH_TIMEOUT * Math.pow(CONFIG.RETRY_MULTIPLIER, attempt),
      CONFIG.MAX_RETRY_DELAY
    );
    setTimeout(callback, delay);
  }

  // ============ FLAG LOADING ============

  const preloadedFlags = new Set();

  /**
   * Preloads country flag images into a hidden container for faster rendering.
   * @function preloadFlags
   * @param {string[]} codes - Array of ISO country codes to preload flags for
   * @returns {void}
   */
  function preloadFlags(codes) {
    const flagContainer = document.createElement("div");
    flagContainer.id = "preloadFlags";
    flagContainer.hidden = true;
    document.body.appendChild(flagContainer);

    const newImages = codes
      .filter(code => !preloadedFlags.has(code))
      .map(code => {
        const img = new Image();
        img.src = `/flags.jsp?c=${code}`;
        flagContainer.appendChild(img);
        return new Promise((resolve) => {
          img.onload = () => {
            preloadedFlags.add(code);
            resolve();
          };
          img.onerror = () => {
            if (debugging) console.warn(`Failed to load flag: ${code}`);
            resolve(); // Resolve anyway to not block Promise.all
          };
        });
      });

    Promise.all(newImages)
      .then(() => {
        if (flagContainer.parentNode) {
          document.body.removeChild(flagContainer);
        }
      })
      .catch(err => {
        if (debugging) console.warn("Flag preload error:", err);
        if (flagContainer.parentNode) {
          document.body.removeChild(flagContainer);
        }
      });
  }

  // Preload all country flags on init
  preloadFlags(Object.values(map.data.countries).map(country => country.code));

  // ============ ROUTER COUNTS ============

  /**
   * Fetches router data from /netdb, parses country/floodfill/tier counts,
   * and stores them in localStorage for map rendering.
   * @async
   * @function storeRouterCounts
   * @returns {Promise<void>}
   */
  async function storeRouterCounts() {
    try {
      const response = await fetch("/netdb");
      if (!response.ok) throw new Error("HTTP " + response.status);

      const parser = new DOMParser();
      const doc = parser.parseFromString(await response.text(), "text/html");
      const rows = doc.querySelectorAll("#cclist tr");

      routerCounts = {
        countries: {},
        floodfill: {},
        tierX: {}
      };

      rows.forEach(row => {
        const countryLink = row.querySelector('a[href^="/netdb?c="]');
        if (countryLink && row.children[3]) {
          const cc = countryLink.href.split("=")[1];
          const count = parseInt(row.children[3].textContent.trim(), 10);
          if (!isNaN(count)) routerCounts.countries[cc] = count;
        }

        ["floodfill", "tierX"].forEach(type => {
          const sel = type === "floodfill" ? "td.countFF a" : "td.countX a";
          const link = row.querySelector(sel);
          if (link) {
            const cc = link.href.match(/cc=([a-zA-Z]{2})/)?.[1];
            if (cc) {
              const count = parseInt(link.textContent.trim(), 10);
              if (!isNaN(count)) routerCounts[type][cc] = count;
            }
          }
        });
      });

      routerCounts.totals = {
        countries: Object.values(routerCounts.countries).reduce((a, b) => a + b, 0),
        floodfill: Object.values(routerCounts.floodfill).reduce((a, b) => a + b, 0),
        tierX: Object.values(routerCounts.tierX).reduce((a, b) => a + b, 0)
      };

      localStorage.setItem("routerCounts", JSON.stringify(routerCounts));
      localStorage.setItem("currentRouterClass", currentRouterClass);
      retryAttempt = 0;

      if (debugging) console.log("Router counts updated:", routerCounts);
      updateShapeClasses(currentRouterClass);

      if (!fetchIntervalId) {
        fetchIntervalId = setInterval(() => {
          storeRouterCounts();
        }, CONFIG.STORE_INTERVAL);
      }
    } catch (error) {
      if (debugging) console.error("Error fetching router counts:", error);
      localStorage.removeItem("routerCounts");
      localStorage.removeItem("currentRouterClass");
      scheduleRetry(storeRouterCounts, ++retryAttempt);
    }
  }

  // Use module-level routerCounts (no localStorage parse needed)
  /**
   * Gets the router count for a specific country/shape and classification.
   * @function getRouterCount
   * @param {string} shapeId - The country/shape identifier
   * @param {string} [routerClass="countries"] - The classification type (countries, floodfill, tierX)
   * @returns {number} The router count for the given shape and class
   */
  function getRouterCount(shapeId, routerClass = "countries") {
    const code = map.data.countries[shapeId]?.code;
    return code ? (routerCounts[routerClass]?.[code] || 0) : 0;
  }

  function getRouterTotalByClass(routerClass) {
    return routerCounts.totals?.[routerClass] || 0;
  }

  /**
   * Gets the total router count for a specific classification.
   * @function getRouterTotalByClass
   * @param {string} routerClass - The classification type (countries, floodfill, tierX)
   * @returns {number} The total count, or 0 if unavailable
   */

  // ============ UI UPDATES ============

  /**
   * Updates the CSS class of an SVG shape based on the router count threshold.
   * @function updateShapeClass
   * @param {string} shapeId - The SVG element ID to update
   * @param {number} count - The router count for this shape
   * @returns {void}
   */
  function updateShapeClass(shapeId, count) {
    const svgElement = document.getElementById(shapeId);
    if (!svgElement) return;

    if (debugging && verbose) {
      console.log(`Updating ${shapeId}: count=${count}`);
    }

    // Remove all count classes efficiently using precomputed Set
    svgElement.classList.remove(...COUNT_CLASSES);

    // Find matching threshold (array already in descending order)
    const match = COUNT_THRESHOLDS.find(({ threshold }) => count >= threshold);
    if (match) svgElement.classList.add(match.className);
  }

  /**
   * Updates all SVG shape classes for the given router classification.
   * @function updateShapeClasses
   * @param {string} routerClass - The classification type to update
   * @returns {void}
   */
  function updateShapeClasses(routerClass) {
    if (!routerCounts[routerClass]) return;

    // Use requestIdleCallback for non-critical updates when available
    const updateFn = () => {
      Object.keys(map.data.countries).forEach(shapeId => {
        updateShapeClass(shapeId, getRouterCount(shapeId, routerClass));
      });
    };

    if ('requestIdleCallback' in window) {
      requestIdleCallback(updateFn, { timeout: 100 });
    } else {
      requestAnimationFrame(updateFn);
    }
  }

  /**
   * Populates the infobox element with country data, flag, and router count.
   * @function createInfobox
   * @param {Object} data - Country data with region and code
   * @param {string} infoboxTemplate - HTML template for the infobox
   * @param {string} shapeId - The country/shape name
   * @param {string} [routerClass="countries"] - The current classification type
   * @returns {void}
   */
  function createInfobox(data, infoboxTemplate, shapeId, routerClass = "countries") {
    if (!data) return;

    const routerCount = getRouterCount(shapeId, routerClass);
    const tierName = routerClass !== "countries"
      ? routerClass.replace("tier", "Bandwidth tier ").replace("floodfill", "Floodfills")
      : "";

    // Use template helper for cleaner substitution
    let html = renderTemplate(infoboxTemplate, { shapeId, data });

    // Special case replacements
    html = html
      .replace(/<b>Routers: 0<\/b>/g, `<b>${tierName || "Routers"}:</b> ${routerCount}`)
      .replace("United States of America", "USA");

    infobox.innerHTML = html;
    infobox.classList.remove("hidden");
  }

  /**
   * Renders the total router count in the infobox for the current classification.
   * @function renderTotals
   * @returns {void}
   */
  function renderTotals() {
    const label = currentRouterClass === "floodfill" ? "Floodfills" :
                  currentRouterClass === "tierX" ? "X tier Routers" : "Routers";
    const total = getRouterTotalByClass(currentRouterClass);

    requestAnimationFrame(() => {
      infobox.innerHTML = `<b>Known ${label}:</b> ${total}`;
    });
  }

  // ============ EVENT HANDLERS ============

  // Define debounced functions once at module scope (fixes recreation bug)
  const debouncedHandleEvent = debounce(handleEventCore, CONFIG.DEBOUNCE_DELAY);
  const debouncedRenderTotals = debounce(renderTotals, CONFIG.DEBOUNCE_DELAY);

  /**
   * Core handler for mouse events on map paths.
   * @function handleEventCore
   * @param {Event} event - The mouse event
   * @returns {void}
   */
  function handleEventCore(event) {
    const target = event.target;
    const sectionId = target?.parentNode?.id;

    if (target?.matches("path[id]")) {
      const shapeId = target.id;
      target.classList.add("hover");

      const data = map.data[sectionId]?.[shapeId];
      if (data) {
        createInfobox(data, map.infoboxHTML[sectionId], shapeId, currentRouterClass);
        const count = getRouterCount(shapeId, currentRouterClass);
        target.classList.toggle("link", count > 0);

        // Prefetch netdb page for countries with routers
        const caps = getCapsParam();
        if (count > 0 && !document.querySelector('link[href="/netdb?c=' + data.code + caps + '"]')) {
          const link = document.createElement("link");
          link.rel = "prefetch";
          link.href = "/netdb?c=" + data.code + caps;
          document.head.appendChild(link);
        }
      }
    } else {
      renderTotals();
    }
  }

  // Throttled DOM repositioning for mousemove (reduces layout thrashing)
  let lastRepositionTime = 0;
  const REPOSITION_THROTTLE = 100; // ms

  /**
   * Gets the caps query parameter value based on the current router class.
   * @function getCapsParam
   * @returns {string} The caps parameter string (e.g., "&caps=f" for floodfill, "&caps=X" for tierX)
   */
  function getCapsParam() {
    if (currentRouterClass === "floodfill") return "&caps=f";
    if (currentRouterClass === "tierX") return "&caps=X";
    return "";
  }

  /**
   * Handles throttled DOM repositioning of hovered path elements.
   * @function handleMouseReposition
   * @param {Element} target - The target element to reposition
   * @returns {void}
   */
  function handleMouseReposition(target) {
    const now = performance.now();
    if (now - lastRepositionTime < REPOSITION_THROTTLE) return;

    if (target.tagName === "path" && target.parentNode?.tagName === "g") {
      const container = target.parentNode;
      const state = elementState.get(target) || {};

      if (state.previousPos === undefined) {
        state.previousPos = Array.from(container.children).indexOf(target);
        elementState.set(target, state);
      }

      if (target !== container.lastElementChild) {
        container.appendChild(target);
        lastRepositionTime = now;
      }
    }
  }

  // ============ EVENT BINDINGS ============

  geomap.addEventListener("mouseenter", debouncedHandleEvent);

  geomap.addEventListener("mouseout", ({ target }) => {
    // Restore element position using WeakMap state
    const state = elementState.get(target);
    if (state?.previousPos !== undefined) {
      const container = target.parentNode;
      const children = Array.from(container.children);
      const referenceNode = children[state.previousPos];
      if (referenceNode && referenceNode !== target) {
        container.insertBefore(target, referenceNode);
      }
    }
    debouncedRenderTotals();
  });

  geomap.addEventListener("mouseleave", () => {
    renderTotals();
    // Remove hover state from all paths
    geomap.querySelectorAll("path.hover").forEach(el => el.classList.remove("hover"));
  });

  geomap.addEventListener("mousemove", event => {
    const { target } = event;
    debouncedHandleEvent(event);
    handleMouseReposition(target);
  });

  geomap.addEventListener("click", (e) => {
    const path = e.target.closest("path[id]");
    if (!path) return;

    const country = map.data.countries[path.id];
    const count = getRouterCount(path.id, currentRouterClass);

    if (country && count > 0) {
      path.classList.add("link");
      const caps = getCapsParam();
      window.location.href = "/netdb?c=" + country.code + caps;
    }
  });

  // ============ INITIALIZATION ============

  /**
   * Highlights the active navigation button based on the current URL query parameter.
   * @function setNavButtonActive
   * @returns {void}
   */
  function setNavButtonActive() {
    const active = getQueryParameter("class");
    const navButtons = document.querySelectorAll("#nav span");
    navButtons.forEach(btn => btn.classList.remove("active"));

    const activeIdMap = { floodfill: "byff", tierX: "byX" };
    const activeId = activeIdMap[active] || "bycc";
    document.getElementById(activeId)?.classList.add("active");
  }

  document.addEventListener("DOMContentLoaded", () => {
    const routerClassFromURL = getQueryParameter("class") || "countries";
    currentRouterClass = routerClassFromURL;

    localStorage.setItem("currentRouterClass", routerClassFromURL);
    storeRouterCounts();

    if (debugging) console.log("Initialized with routerClass:", routerClassFromURL);

    updateShapeClasses(routerClassFromURL);
    setNavButtonActive();

    // Delayed style removal for smooth load
    setTimeout(() => {
      const countriesGroup = geomap.querySelector("#countries");
      if (countriesGroup) countriesGroup.removeAttribute("style");
    }, 300);
  });

  // ============ CLEANUP ============

  window.addEventListener("beforeunload", () => {
    if (fetchIntervalId) {
      clearInterval(fetchIntervalId);
      fetchIntervalId = null;
    }
    netdbWorker.terminate();
  });

})();