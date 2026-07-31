/**
 * @module stats
 * @description Handles periodic refresh and tab switching for the /stats page.
 * Fetches updated stat data via SharedWorker and replaces stat list elements.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  const infohelp = document.querySelector("#gatherstats");
  const nav = document.querySelector(".confignav");
  const tabs = document.querySelectorAll(".togglestat");
  const REFRESH_INTERVAL = 60*1000;

  const fetchWorker = new SharedWorker("/js/fetchWorker.js");
  fetchWorker.port.start();
  fetchWorker.port.onmessage = function(e) {
    const { responseText } = e.data;
    if (!responseText) return;

    const parser = new DOMParser();
    const doc = parser.parseFromString(responseText, "text/html");

    requestAnimationFrame(() => {
      const info = document.getElementById("gatherstats");
      if (info) {
        const infoResponse = doc.getElementById("gatherstats");
        if (infoResponse && !Object.is(info.innerHTML, infoResponse.innerHTML)) {
          info.innerHTML = infoResponse.innerHTML;
        }
      }
      const statlistElements = document.querySelectorAll(".statlist");
      const statlistResponseElements = doc.querySelectorAll(".statlist");
      statlistResponseElements.forEach((statlistResponse, index) => {
        if (index < statlistElements.length) {
          const statlist = statlistElements[index];
          const statlistParent = statlist.parentNode;
          if (statlist.innerHTML !== statlistResponse.innerHTML) {
            const newStatlist = document.importNode(statlistResponse, true);
            statlistParent.replaceChild(newStatlist, statlist);
          }
        }
      });
      progressx.hide();
    });
  };

  function getStatsUrl() {
    const statFilter = new URLSearchParams(window.location.search).get("stat");
    return statFilter ? "/stats?stat=" + encodeURIComponent(statFilter) : "/stats";
  }

  function updateStats() {
    progressx.show(theme);
    progressx.progress(0.5);
    fetchWorker.port.postMessage({url: getStatsUrl(), force: true});
  }

  function initRefresh() {setInterval(updateStats, REFRESH_INTERVAL);}

  function initTabs() {
    for (let i = 0; i < tabs.length; i++) {tabs[i].classList.remove("tab2");}
  }

  nav.addEventListener("click", function(element) {
    if (element.target.classList.contains("togglestat")) {
      if (infohelp) {infohelp.remove();}
      updateStats();
      initTabs();
      element.target.classList.add("tab2");
      progressx.hide();
    }
  });

  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    initTabs();
    progressx.hide();
  });
})();