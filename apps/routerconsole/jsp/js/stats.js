/**
 * @module stats
 * @description Handles periodic refresh and tab switching for the /stats page.
 * Fetches updated stat data via XHR and replaces stat list elements.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  const infohelp = document.querySelector("#gatherstats");
  const nav = document.querySelector(".confignav");
  const routerTab = document.getElementById("Router");
  const tabs = document.querySelectorAll(".togglestat");
  const REFRESH_INTERVAL = 60*1000;

  /**
   * Starts the periodic stats refresh interval.
   * @function initRefresh
   * @returns {void}
   */
  function initRefresh() {setInterval(updateStats, REFRESH_INTERVAL);}

  /**
   * Fetches the latest stats page via XHR and updates stat list elements.
   * @function updateStats
   * @returns {void}
   */
  function updateStats() {
    progressx.show(theme);
    progressx.progress(0.5);
    const xhrstats = new XMLHttpRequest();
    xhrstats.open("GET", "/stats", true);
    xhrstats.responseType = "document";
    xhrstats.onreadystatechange = function () {
      if (xhrstats.readyState === 4 && xhrstats.status === 200) {
        const info = document.getElementById("gatherstats");
        if (info) {
          const infoResponse = xhrstats.responseXML.getElementById("gatherstats");
          if (infoResponse && !Object.is(info.innerHTML, infoResponse.innerHTML)) {
            info.innerHTML = infoResponse.innerHTML;
          }
        }
        const statlistElements = document.querySelectorAll(".statlist");
        const statlistResponseElements = xhrstats.responseXML.querySelectorAll(".statlist");
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
      }
    }
    xhrstats.send();
    progressx.hide();
  }

  /**
   * Removes the "tab2" class from all toggle stat tab elements.
   * @function initTabs
   * @returns {void}
   */
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