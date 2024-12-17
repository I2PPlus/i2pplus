/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* License: AGPL3 or later */

import { onVisible, onHidden } from "/js/onVisible.js";

(function() {
  const d = document;
  const $ = id => d.getElementById(id);
  const query = selector => d.querySelector(selector);

  const form = $("gform");
  const configs = $("graphConfigs");
  const allgraphs = $("allgraphs");
  const h3 = $("graphdisplay");
  const sb = $("sidebar");
  let graphsTimerId;
  let longestLoadTime = 500;
  let lastRefreshTime = 0;
  let previousRefreshInterval = 0;
  let debugging = false;

  if (configs) configs.hidden = true;

  const updateGraphs = async () => {
    if (graphRefreshInterval <= 0) return;
    progressx.show(theme);
    progressx.progress(0.5);
    clearInterval(graphsTimerId);
    graphsTimerId = setInterval(updateGraphs, graphRefreshInterval);

    const images = [...d.querySelectorAll(".statimage")];
    images.forEach(img => img.classList.add("lazy"));
    const now = Date.now();
    const timeSinceLastRefresh = now - lastRefreshTime;
    const allLoaded = images.every(img => img.complete);
    if (timeSinceLastRefresh < longestLoadTime || !allLoaded) {
      const newRefreshInterval = Math.max(longestLoadTime + 1000 - timeSinceLastRefresh, graphRefreshInterval);
      if (newRefreshInterval !== previousRefreshInterval && newRefreshInterval !== graphRefreshInterval) {
        previousRefreshInterval = newRefreshInterval;
        if (debugging) {
          console.log(`Not all images loaded in the allocated time (${graphRefreshInterval}ms), updating refresh interval to: ${newRefreshInterval}ms`);
        }
      } else if (newRefreshInterval === graphRefreshInterval) {
        previousRefreshInterval = newRefreshInterval;
      }
      graphsTimerId = setTimeout(updateGraphs, newRefreshInterval);
      return;
    }

    lastRefreshTime = now;
    const startTime = Date.now();
    const visibleImages = images.filter(image => !image.classList.contains("lazyhide"));
    const lazyImages = images.filter(image => image.classList.contains("lazyhide"));

    await Promise.all(visibleImages.map(async image => {
      const imageSrc = image.src.replace(/time=\d+/, `time=${now}`);
      const response = await fetch(imageSrc);
      if (response.ok) {image.src = imageSrc;}
    })).catch(error => {});

    progressx.hide();
    const endTime = Date.now();
    const totalTime = endTime - startTime;
    longestLoadTime = Math.max(longestLoadTime, totalTime);
    if (debugging) {console.log(`Total load time for all visible images: ${totalTime}ms`);}

    await new Promise(resolve => setTimeout(resolve, 5000));

    await Promise.all(lazyImages.map(async image => {
      const lazyImageSrc = image.src.replace(/time=\d+/, "time=" + Date.now());
      const lazyResponse = await fetch(lazyImageSrc);
      if (lazyResponse.ok) { image.src = lazyImageSrc; }
    })).catch(error => {});
  };

  const stopRefresh = () => clearInterval(graphsTimerId);

  const isDown = () => {
    const images = [...d.querySelectorAll(".statimage")];
    if (!images.length) {
      allgraphs.innerHTML = "<span id=nographs><b>No connection to Router</b></span>";
      progressx.hide();
    }
    setTimeout(initCss, 5000);
  };

  const toggleView = () => {
    const toggle = $("toggleSettings");
    if (!toggle) {return;}
    const isHidden = toggle.checked ? false : true;
    form.hidden = isHidden;
    toggle.checked = !isHidden;
    h3.classList[isHidden ? "remove" : "add"]("visible");
    if (!isHidden) {
      $("gwidth")?.focus();
      if (sb && sb.scrollHeight < d.body.scrollHeight) {
        setTimeout(() => window.scrollTo({ top: d.body.scrollHeight, behavior: "smooth" }), 500);
      }
    }
  };

  const loadToggleCss = () => {
    const css = query("#graphToggleCss");
    if (!css) {
      const link = d.createElement("link");
      link.href = "/themes/console/graphConfig.css";
      link.rel = "stylesheet";
      link.id = "graphToggleCss";
      d.head.appendChild(link);
    }
  };

  const initCss = () => {
    const graph = query(".statimage");
    if (!graph) return;

    graph.addEventListener("load", () => {
      const gwrap = query("style#gwrap");
      if (!gwrap) return;
      if (!d.body.classList.contains("loaded")) {
        const widepanel = query(".widepanel");
        const delay = Math.max(graphCount * 5, 120);
        widepanel.id = "nographs";
        const graphWidth = graph.naturalWidth > 40 ? graph.naturalWidth : 0;
        const graphHeight = graph.naturalHeight;
        const dimensions = `.graphContainer{width:${graphWidth + 4}px;height:${graphHeight + 4}px}`;

        if (graphWidth !== "auto" && graphWidth !== "0" && !dimensions.includes("width:4px")) {
          gwrap.innerText = dimensions;
          d.body.classList.add("loaded");
        } else {gwrap.innerText = "";}

        setTimeout(() => {
          widepanel.id = "";
          allgraphs.removeAttribute("hidden");
          configs.removeAttribute("hidden");
        }, delay);
      }
    });
    if (graph.complete) graph.dispatchEvent(new Event("load"));
  };

  d.addEventListener("DOMContentLoaded", () => {
    initCss();
    onVisible(allgraphs, updateGraphs);
    onHidden(allgraphs, stopRefresh);
    loadToggleCss();
    toggleView();
    const toggle = $("toggleSettings");
    toggle?.addEventListener("click", toggleView);
    progressx.hide();
  });

  setTimeout(isDown, 60000);

})();