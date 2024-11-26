/* I2P+ initSidebar.js by dr|z3d */
/* Initialize console sidebar */
/* License: AGPL3 or later */

import {refreshSidebar, requestIdleOrAnimationFrame} from "/js/refreshSidebar.js";
import {miniGraph} from "/js/miniGraph.js";
import {sectionToggler, countNewsItems} from "/js/sectionToggle.js";
import {stickySidebar} from "/js/stickySidebar.js";
import {onVisible} from "/js/onVisible.js";

export let refreshInterval = refresh * 1000;

const setupSidebar = () => {
  sectionToggler();
  stickySidebar();
  miniGraph();
};

const initSidebarRefresh = () => {
  let isRefreshing = false;
  const refreshSidebarThrottled = () => {
    if (!isRefreshing) {
      isRefreshing = true;
      requestIdleOrAnimationFrame(() => {
        refreshSidebar();
        miniGraph();
        isRefreshing = false;
      });
    }
  };
  clearInterval(window.sbRefreshTimerId);
  window.sbRefreshTimerId = setInterval(refreshSidebarThrottled, refreshInterval);
}

const stopSidebarRefresh = () => {clearInterval(window.sbRefreshTimerId);}

const onDomContentLoaded = () => {
  setupSidebar();
  const sb = document.querySelector("#sidebar");
  if (sb) {onVisible(sb, initSidebarRefresh, stopSidebarRefresh);}
  if (refresh > 0) {initSidebarRefresh();}
}

if (refresh > 0) {onVisible(document.body, onDomContentLoaded, onDomContentLoaded);}
else {document.addEventListener("DOMContentLoaded", onDomContentLoaded);}

window.addEventListener("resize", stickySidebar);