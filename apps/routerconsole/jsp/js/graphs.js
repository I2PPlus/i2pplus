/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* License: AGPL3 or later */

import {onVisible, onHidden} from "/js/onVisible.js";

(function() {
  const form = document.getElementById("gform");
  const configs = document.getElementById("graphConfigs");
  const allgraphs = document.getElementById("allgraphs");
  const h3 = document.getElementById("graphdisplay");
  const sb = document.getElementById("sidebar");
  const submit = form.querySelector(".accept");
  const toggle = document.getElementById("toggleSettings");
  let graphsTimerId;
  let longestLoadTime = 500;
  let lastRefreshTime = 0;
  let debugging = false;

  if (configs !== null) {toggle.hidden = true;}

  function initCss() {
    const graph = document.querySelector(".statimage");

    graph.addEventListener("load", () => {
      const gwrap = document.head.querySelector("style#gwrap");
      if (!gwrap) {return;}
      if (!document.body.classList.contains("loaded")) {
        const widepanel = document.querySelector(".widepanel");
        const delay =  Math.max(graphCount*5, 120);
        widepanel.id = "nographs";
        const graphWidth = graph.naturalWidth > 40 ? graph.naturalWidth : 0;
        const graphHeight = graph.naturalHeight;
        const dimensions = ".graphContainer{width:" + (graphWidth + 4) + "px;height:" + (graphHeight + 4) + "px}";

        if (graphWidth !== "auto" && graphWidth !== "0" && dimensions.indexOf("width:4px") === -1) {
          gwrap.innerText = dimensions;
          document.body.classList.add("loaded");
        } else {gwrap.innerText = "";}

        setTimeout(() => {
          widepanel.id = "";
          allgraphs.removeAttribute("hidden");
          configs.removeAttribute("hidden");
        }, delay);
      }
    });
    if (graph.complete) { graph.dispatchEvent(new Event("load")); }
  }

  function updateGraphs() {
    if (graphRefreshInterval <= 0) {return;}
    progressx.show(theme);
    progressx.progress(0.5);
    stopRefresh();
    graphsTimerId = setInterval(updateGraphs, graphRefreshInterval);
    const images = document.querySelectorAll(".statimage");
    const now = Date.now();
    const timeSinceLastRefresh = now - lastRefreshTime;
    const allLoaded = [...images].every(img => img.complete);
    if (timeSinceLastRefresh < longestLoadTime || !allLoaded) {return;}
    lastRefreshTime = now;

    const startTime = Date.now();

    const promises = Array.from(images).map((image) => {
      const imageSrc = image.src.replace(/time=\d+/, "time=" + now);
      return fetch(imageSrc).then((response) => {
        if (response.ok) {
          return new Promise((resolve) => {
            requestAnimationFrame(() => {
              image.src = imageSrc;
              resolve();
            });
          });
        }
      });
    });

    Promise.all(promises).then(() => {
      progressx.hide();
      const endTime = Date.now();
      const totalTime = endTime - startTime;
      longestLoadTime = Math.max(longestLoadTime, totalTime);
      if (debugging) {console.log("Total load time for all images: " + totalTime + "ms");}
    });
  }

  function stopRefresh() { if (graphsTimerId) {clearInterval(graphsTimerId);} }

  function isDown() {
    const images = document.querySelectorAll(".statimage");
    let totalImages = images.length;
    if (!images.length) {
      graphs.innerHTML = "<span id=nographs><b>No connection to Router<\/b><\/span>";
      progressx.hide();
    }
    setTimeout(() => { initCss(); }, 5*1000);
  }

  function toggleView() {
    if (!toggle) {return;}
    if (toggle.checked === false) {
      form.hidden = true;
      toggle.removeAttribute("checked");
      if (h3.classList.contains("visible")) {h3.classList.remove("visible");}
    } else {
      form.hidden = false;
      toggle.setAttribute("checked", "checked");
      if (!h3.classList.contains("visible")) {h3.classList.add("visible");}
      document.getElementById("gwidth").focus();
      if (sb !== null && sb.scrollHeight < document.body.scrollHeight) {
        setTimeout(() => {window.scrollTo({top: document.body.scrollHeight, behavior: "smooth"});}, 500);
      }
    }
  }

  function loadToggleCss() {
    const css = document.querySelector("#graphToggleCss");
    if (css) {return;}
    const link = document.createElement("link");
    link.href = "/themes/console/graphConfig.css";
    link.rel = "stylesheet";
    link.id = "graphToggleCss";
    document.head.appendChild(link);
  }

  document.addEventListener("DOMContentLoaded", function() {
    initCss();
    onVisible(allgraphs, updateGraphs);
    onHidden(allgraphs, stopRefresh);
    loadToggleCss();
    toggleView();
    toggle?.addEventListener("click", toggleView);
    progressx.hide();
  });

  setTimeout(isDown, 60000);

})();
  
