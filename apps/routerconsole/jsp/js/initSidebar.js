import { refreshSidebar, requestIdleOrAnimationFrame } from "/js/refreshSidebar.js";
import { miniGraph } from "/js/miniGraph.js";
import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { onVisible, onHidden } from "/js/onVisible.js";
export let refreshInterval = refresh * 1000;
export let isDocumentVisible = document.visibilityState === "visible";

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
      requestIdleOrAnimationFrame(() => {
        refreshSidebar();
        miniGraph();
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

  const onDomContentLoaded = () => { setupSidebar(); };

  document.addEventListener("visibilitychange", () => {
    isDocumentVisible = !document.hidden;
    const sb = document.getElementById("sidebar");
    if (sb) {
      if (isDocumentVisible) {
        onVisible(sb, startSidebarRefresh);
      } else {
        onHidden(sb, stopSidebarRefresh);
      }
    }
  });

  if (refresh > 0) { onVisible(document.body, onDomContentLoaded, onDomContentLoaded); }
  else { document.addEventListener("DOMContentLoaded", onDomContentLoaded); }
  onDomContentLoaded();
  window.addEventListener("resize", stickySidebar);
})();