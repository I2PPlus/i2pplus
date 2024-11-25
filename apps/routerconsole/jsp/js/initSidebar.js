/* I2P+ initSidebar.js by dr|z3d */
/* Initialize console sidebar */
/* License: AGPL3 or later */

import {refreshSidebar, requestIdleOrAnimationFrame} from "/js/refreshSidebar.js";
import {sectionToggler, countNewsItems} from "/js/sectionToggle.js";
import {stickySidebar} from "/js/stickySidebar.js";
import {onVisible} from "/js/onVisible.js";

const setupSidebar = () => {
  sectionToggler();
  stickySidebar();
};

if (refresh > 0) {
  window.refreshInterval = refresh * 1000;
  window.sbRefreshTimerId = null;
  const initSidebarRefresh = () => {
    clearInterval(window.sbRefreshTimerId);
    window.sbRefreshTimerId = setInterval(() => {requestIdleOrAnimationFrame(refreshSidebar);}, refreshInterval);
  };
  const stopSidebarRefresh = () => {clearInterval(window.sbRefreshTimerId);};
  const onDomContentLoaded = () => {
    const sb = document.querySelector("#sidebar");
    setupSidebar();
    if (window.sbRefreshTimerId === null) {initSidebarRefresh();}
    if (sb) {onVisible(sb, initSidebarRefresh, stopSidebarRefresh);}
  };
  onVisible(document.body, onDomContentLoaded, onDomContentLoaded);
} else {document.addEventListener("DOMContentLoaded", setupSidebar);}

window.addEventListener("resize", stickySidebar);