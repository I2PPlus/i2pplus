/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh, configuration toggle and graph filtering */
/* License: AGPL3 or later */

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

  function debounceFilter(func, delay = 500, input) {
    let timeoutId;
    return () => {
      clearTimeout(timeoutId);
      timeoutId = setTimeout(() => func(input), delay);
    };
  }

  let imgObservers = [];

  function disconnectObservers() {
    imgObservers.forEach((obs) => obs.disconnect());
    imgObservers.length = 0;
  }

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
        { rootMargin: "0px 0px 200px 0px" }
      );
      io.observe(img);
      imgObservers.push(io);
    });
  }

  function initCss() {
    const graph = query(".statimage");
    if (!graph) return;
    const onLoad = () => {
      const styleElem = query("style#gwrap");
      if (!styleElem || d.body.classList.contains("loaded")) return;
      const widepanel = query(".widepanel"),
        dimensions = `.graphContainer{width:${graph.naturalWidth + 4}px;height:${graph.naturalHeight + 4}px}`;
      if (graph.naturalWidth > 40 && !styleElem.innerText.includes("width:4px")) {
        styleElem.innerText = dimensions;
        d.body.classList.add("loaded");
      } else styleElem.innerText = "";
      widepanel.id = "nographs";
      setTimeout(() => {
        widepanel.id = "";
        allgraphs.removeAttribute("hidden");
        configs.removeAttribute("hidden");
      }, Math.max(d.querySelectorAll(".graphContainer").length * 5, 120));
    };
    if (graph.complete) onLoad();
    else graph.addEventListener("load", onLoad);
  }

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
    } catch {}
  }

  if (typeof graphRefreshInterval !== "undefined" && graphRefreshInterval > 0) {
    updateGraphs();
  }

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

  function loadToggleCss() {
    if (!query("#graphToggleCss")) {
      const link = d.createElement("link");
      link.rel = "stylesheet";
      link.href = "/themes/console/graphConfig.css";
      link.id = "graphToggleCss";
      d.head.appendChild(link);
    }
  }

  function isDown() {
    const images = [...d.querySelectorAll(".statimage")];
    if (!images.length) {
      allgraphs.innerHTML = '<span id=nographs><b>No connection to Router</b></span>';
      progressx.hide();
    }
    setTimeout(initCss, 5000);
  }

  const stopRefresh = () => clearInterval(graphsTimerId);

  d.addEventListener("DOMContentLoaded", () => {
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
    initCss();
    onVisible(allgraphs, () => {
      updateGraphs(searchInput, debouncedApplyFilter);
      debouncedApplyFilter();
    });
    onHidden(allgraphs, stopRefresh);
    loadToggleCss();
    toggleView();
    $("toggleSettings")?.addEventListener("click", toggleView);
    setTimeout(isDown, 60000);
    if (debugging) console.log(`Refresh interval is: ${graphRefreshInterval / 1000}s`);
  });
})();