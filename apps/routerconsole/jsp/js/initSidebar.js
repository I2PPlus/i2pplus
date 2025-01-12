import { miniGraph } from "/js/miniGraph.js";
import { refreshSidebar } from "/js/refreshSidebar.js";
import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";

export let refreshInterval = refresh * 1000;
let sbRefreshTimerId = null;
let refreshActive = true;

(() => {

  Object.defineProperty(window, "sbRefreshTimerId", {
    get: () => sbRefreshTimerId,
    enumerable: true,
    configurable: true
  });

  const setupSidebar = () => {
    sectionToggler();
    stickySidebar();
    miniGraph();
  };

  let refreshQueue = [];
  let isRefreshing = false;
  let refreshTimeout;

  const processQueue = () => {
    if (refreshQueue.length > 0) {
      isRefreshing = true;
      const nextRefresh = refreshQueue.shift();
      requestAnimationFrame(() => {
        refreshSidebar();
        isRefreshing = false;
        processQueue();
      });
    }
  };

  window.refreshSidebarThrottled = () => {
    if (isRefreshing) { refreshQueue.push(Date.now()); }
    else { processQueue(); }
    clearTimeout(refreshTimeout);
    refreshTimeout = setTimeout(() => {
      if (!isRefreshing && refreshQueue.length > 0) { processQueue(); }
    }, refreshInterval);
  };

  const startSidebarRefresh = () => {
    refreshActive = true;
    sbRefreshTimerId = setInterval(window.refreshSidebarThrottled, refreshInterval);
  };

  const stopSidebarRefresh = () => {
    refreshActive = false;
    clearInterval(sbRefreshTimerId);
    clearTimeout(refreshTimeout);
    refreshQueue = [];
  };

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {startSidebarRefresh();}
    else {stopSidebarRefresh();}
  });

  document.addEventListener("DOMContentLoaded", setupSidebar);
  window.addEventListener("resize", stickySidebar, {passive: true});
})();

export async function getRefreshTimerId() {
  return {timerId: window.sbRefreshTimerId, isActive: refreshActive};
}