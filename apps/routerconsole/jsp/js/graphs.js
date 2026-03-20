/**
 * @module graphs
 * @description Handles AJAX graph refresh, configuration panel toggle, and
 * graph filtering on the /graphs page. Supports lazy loading of graph images,
 * visibility-based refresh scheduling, and persistent filter state via localStorage.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { onVisible, onHidden } from "/js/onVisible.js";

(() => {
  const d = document,
    $ = (id) => d.getElementById(id),
    query = (s) => d.querySelector(s);

  const form = $("gform"),
    configs = $("graphConfigs"),
    allgraphs = $("allgraphs"),
    h3 = $("graphdisplay"),
    sb = $("sidebar");

  let graphsTimerId,
    longestLoadTime = 500,
    lastRefreshTime = 0,
    debugging = false;

  /**
   * Creates and appends a search filter UI element above the graph list.
   * @function initializeSearchFilter
   * @returns {{searchInput: HTMLInputElement, clearButton: HTMLButtonElement}} The created filter elements
   */
  function initializeSearchFilter() {
    const container = query("h1.perf");
    if (!container) return {};
    const searchContainer = d.createElement("div");
    searchContainer.id = "graphFilter";
    const searchInput = d.createElement("input");
    searchInput.type = "text";
    searchInput.id = "searchInput";
    const clearButton = d.createElement("button");
    clearButton.textContent = "Clear Filter";
    clearButton.addEventListener("click", () => {
      searchInput.value = "";
      searchInput.dispatchEvent(new Event("input"));
      localStorage.removeItem("graphsFilter");
    });
    searchContainer.append(searchInput, clearButton);
    container.insertBefore(searchContainer, container.firstChild);
    return { searchInput, clearButton };
  }

  /**
   * Filters graph containers based on the search input text and alias map.
   * @function applyFilter
   * @param {HTMLInputElement} input - The search input element containing the filter text
   * @returns {void}
   */
  function applyFilter(input) {
    const text = input.value.trim().toLowerCase(),
      aliasMap = {
        bandwidth: { include: ["bw", "bps", "bandwidth", "combined"], exclude: ["jobwait"] },
        count: { include: ["count", "participatingtunnels"], exclude: [] },
        ssu: { include: ["udp", "ssu"], exclude: [] },
        tco: { include: ["ntcp", "tcp"], exclude: [] },
        transit: { include: ["participating", "transit"], exclude: [] },
      },
      entry = aliasMap[text];

    d.querySelectorAll(".graphContainer").forEach((container) => {
      const img = container.querySelector("img");
      if (!img) {
        container.style.display = "none";
        return;
      }
      const alt = (img.alt || "").toLowerCase();
      if (!text) {
        container.style.display = "";
        container.classList.remove("loading");
        return;
      }
      if (!entry) {
        const matched = alt.includes(text);
        container.style.display = matched ? "" : "none";
        if (matched) container.classList.remove("loading");
        return;
      }
      const includesAny = entry.include.some((inc) => alt.includes(inc)),
        excludesAny = entry.exclude.some((exc) => alt.includes(exc)),
        matched = includesAny && !excludesAny;
      container.style.display = matched ? "" : "none";
      if (matched) container.classList.remove("loading");
    });

    localStorage.setItem("graphsFilter", input.value.trim());
  }

  /**
   * Creates a debounced version of the filter function.
   * @function debounceFilter
   * @param {Function} func - The function to debounce
   * @param {number} [delay=500] - Debounce delay in milliseconds
   * @param {HTMLInputElement} input - The input element to pass to the function
   * @returns {Function} The debounced filter function
   */
  function debounceFilter(func, delay = 500, input) {
    let timeoutId;
    return () => {
      clearTimeout(timeoutId);
      timeoutId = setTimeout(() => func(input), delay);
    };
  }

  let imgObservers = [];

  /**
   * Disconnects all active IntersectionObservers and clears the array.
   * @function disconnectObservers
   * @returns {void}
   */
  function disconnectObservers() {
    imgObservers.forEach((obs) => obs.disconnect());
    imgObservers.length = 0;
  }

  /**
   * Sets up MutationObserver on the target and IntersectionObservers for each graph image.
   * @function initializeObservers
   * @param {HTMLElement} target - The container element to observe for mutations
   * @param {Function} debouncedFunc - The debounced filter function to call on changes
   * @param {HTMLInputElement} input - The filter input element
   * @returns {void}
   */
  function initializeObservers(target, debouncedFunc, input) {
    disconnectObservers();
    if (target) new MutationObserver(debouncedFunc).observe(target, { childList: true, subtree: true });
    d.querySelectorAll(".graphContainer").forEach((container) => {
      if (container.style.display === "none") return;
      const img = container.querySelector("img");
      if (!img) return;
      const io = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (entry.isIntersecting) {
              debouncedFunc();
              io.unobserve(entry.target);
            }
          });
        },
        { rootMargin: "200px 0px 200px 0px" }
      );
      io.observe(img);
      imgObservers.push(io);
    });
  }

  /**
   * Applies the natural dimensions of the first graph image to the container style.
   * @async
   * @function applyDimensions
   * @returns {Promise<boolean>} True if dimensions were applied successfully
   */
  async function applyDimensions() {
    const styleElem = query("style#gwrap");
    if (!styleElem) return false;

    const firstImg = query(".statimage");
    if (!firstImg) return false;

    try {
      await firstImg.decode();
    } catch (error) {
      return false;
    }

    const w = firstImg.naturalWidth;
    const h = firstImg.naturalHeight;
    if (w <= 40 || h <= 20) {
      return false;
    }

    const css = `.graphContainer { width: ${w + 4}px; height: ${h + 4}px; }`;
    if (styleElem.innerText !== css) {
      styleElem.innerText = css;
    }
    return true;
  }

  /**
   * Retries applying dimensions until successful or no images remain.
   * @function ensureDimensions
   * @returns {void}
   */
  function ensureDimensions() {
    const success = applyDimensions();
    if (!success && d.querySelector(".statimage")) {
      setTimeout(ensureDimensions, 200);
    }
  }

  /**
   * Refreshes all graph images via preloaded images and updates the display.
   * @async
   * @function updateGraphs
   * @param {HTMLInputElement} input - The filter input element
   * @param {Function} debouncedFunc - The debounced filter function to reapply after update
   * @returns {Promise<void>}
   */
  async function updateGraphs(input, debouncedFunc) {
    if (typeof graphRefreshInterval === "undefined" || graphRefreshInterval <= 0) return;
    progressx.show(theme);
    clearInterval(graphsTimerId);
    graphsTimerId = setInterval(() => updateGraphs(input, debouncedFunc), graphRefreshInterval);
    const images = Array.from(d.querySelectorAll(".statimage"));
    images.forEach((img) => img.classList.add("lazy"));
    const now = Date.now(),
      timeSinceLast = now - lastRefreshTime,
      allLoaded = images.every((img) => img.complete);
    lastRefreshTime = now;
    if (timeSinceLast < longestLoadTime || !allLoaded) {
      const nextInterval = Math.max(longestLoadTime + 1000 - timeSinceLast, graphRefreshInterval);
      clearTimeout(graphsTimerId);
      graphsTimerId = setTimeout(() => updateGraphs(input, debouncedFunc), nextInterval);
    }
    const visibleImgs = images.filter((img) => !img.classList.contains("lazyhide")),
      lazyImgs = images.filter((img) => img.classList.contains("lazyhide")),
      start = Date.now();
    try {
      await Promise.all(
        visibleImgs.map(
          (img) =>
            new Promise((res, rej) => {
              const src = img.src.replace(/time=\d+/, `time=${now}`);
              const pre = new Image();
              pre.onload = () => {
                img.src = src;
                res();
              };
              pre.onerror = rej;
              pre.src = src;
            })
        )
      );
    } catch {}
    debouncedFunc?.();
    progressx.hide();
    longestLoadTime = Math.max(longestLoadTime, Date.now() - start);
    if (debugging) console.log(`Total load time for all visible images: ${longestLoadTime}ms`);
    await new Promise((res) => setTimeout(res, 5000));
    try {
      await Promise.all(
        lazyImgs.map(async (img) => {
          const src = img.src.replace(/time=\d+/, `time=${Date.now()}`);
          const resp = await fetch(src);
          if (resp.ok) img.src = src;
        })
      );
    } catch(error) {}
  }

  if (typeof graphRefreshInterval !== "undefined" && graphRefreshInterval > 0) {
    updateGraphs();
  }

  /**
   * Toggles the visibility of the graph configuration panel.
   * @function toggleView
   * @returns {void}
   */
  function toggleView() {
    const toggle = $("toggleSettings");
    if (!toggle) return;
    const hidden = !toggle.checked;
    form.hidden = hidden;
    toggle.checked = !hidden;
    h3.classList.toggle("visible", !hidden);
    if (!hidden) {
      $("gwidth")?.focus();
      if (sb && sb.scrollHeight < d.body.scrollHeight)
        setTimeout(() => window.scrollTo({ top: d.body.scrollHeight, behavior: "smooth" }), 500);
    }
  }

  /**
   * Dynamically loads the graph configuration toggle CSS if not already loaded.
   * @function loadToggleCss
   * @returns {void}
   */
  function loadToggleCss() {
    if (!query("#graphToggleCss")) {
      const link = d.createElement("link");
      link.rel = "stylesheet";
      link.href = "/themes/console/graphConfig.css";
      link.id = "graphToggleCss";
      d.head.appendChild(link);
    }
  }

  /**
   * Checks if graph images are present; shows an error message if none are loaded.
   * @function isDown
   * @returns {void}
   */
  function isDown() {
    const images = [...d.querySelectorAll(".statimage")];
    if (!images.length) {
      allgraphs.innerHTML = '<span id=nographs><b>No connection to Router</b></span>';
      progressx.hide();
    }
    setTimeout(ensureDimensions, 5000);
  }

  /**
   * Stops the graph refresh interval timer.
   * @function stopRefresh
   * @returns {void}
   */
  const stopRefresh = () => clearInterval(graphsTimerId);

  d.addEventListener("DOMContentLoaded", () => {
    if (!d.body.classList.contains("loaded") && !d.body.classList.contains("ready")) {
      d.body.classList.add("loaded", "ready");
    }
    const { searchInput } = initializeSearchFilter();
    if (!searchInput) return;
    searchInput.value = localStorage.getItem("graphsFilter") || "";
    applyFilter(searchInput);
    const debouncedApplyFilter = debounceFilter(applyFilter, 100, searchInput);
    searchInput.addEventListener("input", () => {
      const val = searchInput.value.trim();
      localStorage.setItem("graphsFilter", val);
      if (val) applyFilter(searchInput);
      initializeObservers(allgraphs, debouncedApplyFilter, searchInput);
    });
    initializeObservers(allgraphs, debouncedApplyFilter, searchInput);
    ensureDimensions();
    onVisible(allgraphs, () => {
      updateGraphs(searchInput, debouncedApplyFilter);
      debouncedApplyFilter();
    });
    onHidden(allgraphs, stopRefresh);
    loadToggleCss();
    toggleView();
    $("graphConfigs").removeAttribute("hidden");
    $("toggleSettings")?.addEventListener("click", toggleView);
    setTimeout(isDown, 60000);
    if (debugging) console.log(`Refresh interval is: ${graphRefreshInterval / 1000}s`);
  });
})();