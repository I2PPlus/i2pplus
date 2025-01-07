import { miniGraph } from "/js/miniGraph.js";
import { refreshSidebar } from "/js/refreshSidebar.js";
import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
export let refreshInterval = refresh * 1000;
export let isDocumentVisible = true;

(() => {

  const setupSidebar = () => {
    sectionToggler();
    stickySidebar();
    miniGraph();
  };

  let refreshQueue = [];
  let isRefreshing = false;
  let refreshTimeout;
  let sbRefreshTimerId;

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
    if (isDocumentVisible) {startSidebarRefresh();}
  };

  const startSidebarRefresh = () => { sbRefreshTimerId = setInterval(window.refreshSidebarThrottled, refreshInterval); };
  const stopSidebarRefresh = () => {
    clearInterval(sbRefreshTimerId);
    clearTimeout(refreshTimeout);
  };

  document.addEventListener("visibilitychange", () => {
    isDocumentVisible = !document.hidden;
    if (isDocumentVisible) {startSidebarRefresh();}
    else {stopSidebarRefresh();}
  });

  document.addEventListener("DOMContentLoaded", setupSidebar);
  window.addEventListener("resize", stickySidebar);
})();