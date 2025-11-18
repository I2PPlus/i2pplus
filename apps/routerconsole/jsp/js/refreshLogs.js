/* I2P+ refreshLogs.js by dr|z3d */
import { onVisible, onHidden } from "/js/onVisible.js";
import morphdom from "/js/morphdom.js";

function start() {
  const $ = (id) => document.getElementById(id);

  const els = {
    mainLogs: $("logs"),
    criticallogs: $("criticallogs"),
    errorCount: $("errorCount"),
    routerlogs: $("routerlogs"),
    routerlogsList: $("routerlogs")?.querySelector("td ul"),
    routerlogsFileInfo: $("routerlogs")?.querySelector("tr:first-child td p"),
    servicelogs: $("wrapperlogs"),
    refreshSpan: $("refreshPeriod"),
    refreshInput: $("logRefreshInterval"),
    toggleRefresh: $("toggleRefresh"),
    filterInput: $("logFilterInput"),
  };

  const state = {
    updates: [],
    worker: new SharedWorker("/js/fetchWorker.js"),
    logsRefreshId: null,
    intervalValue: localStorage.getItem("logsRefresh") || "30",
  };

  if ("SharedWorker" in window) {
    state.worker.port.onmessage = (event) => {
      const { responseText, isDown } = event.data;
      if (isDown) return;

      const parser = new DOMParser();
      const doc = parser.parseFromString(responseText, "text/html");

      processUpdates(doc, els, state);
      requestAnimationFrame(() => {
        state.updates.forEach((fn) => fn());
        postMorphActions();
        state.updates = [];
      });
    };

    state.worker.port.start();
    state.worker.port.postMessage({ url: "/logs" });
  }

  function processUpdates(doc, els, state) {
    const filterValue = encodeURIComponent(els.filterInput?.value?.trim().toLowerCase() || "").replace(/%20/g, " ");

    if (els.errorCount) {
      const newEl = doc.getElementById("errorCount");
      if (newEl) state.updates.push(() => morphdom(els.errorCount, newEl, { childrenOnly: true }));
    }

    if (els.criticallogs) {
      const newEl = doc.getElementById("criticallogs");
      if (newEl) state.updates.push(() => morphdom(els.criticallogs, newEl, { childrenOnly: true }));
    }

    if (els.routerlogsList) {
      const newFileInfo = doc.querySelector("#routerlogs tr:first-child td p");
      const newList = doc.querySelector("#routerlogs td ul");

      if (newFileInfo) state.updates.push(() => morphdom(els.routerlogsFileInfo, newFileInfo));
      if (newList) {
        tagFirstLi(newList);
        state.updates.push(() => morphdom(els.routerlogsList, newList, {
          childrenOnly: true,
          getNodeKey: el => el.nodeType === Node.ELEMENT_NODE ? el.getAttribute("data-key") : null
        }));
      }
    }

    if (els.servicelogs) {
      const newEl = doc.getElementById("wrapperlogs");
      if (newEl) state.updates.push(() => morphdom(els.servicelogs, newEl, { childrenOnly: true }));
    }

    if (els.routerlogsList) {
      els.routerlogsList.querySelectorAll("li").forEach((li) => {
        li.style.display = li.textContent.toLowerCase().includes(filterValue) ? "block" : "none";
      });
    }
  }

  function postMorphActions() {
    const list = document.querySelector("#routerlogs td ul") || document.querySelector("#criticallogs td ul") ;
    if (list) {
      linkifyRouterIds(list);
      linkifyLeaseSets(list);
      linkifyIPv4(list);
      linkifyIPv6(list);
    }
  }

  function initRefresh() {
    if (!els.mainLogs || !els.routerlogs) {
      setTimeout(initRefresh, 500);
      return;
    }
    stopRefresh();
    state.logsRefreshId = setInterval(() => refreshLogs(state, els), state.intervalValue * 1000);
    els.toggleRefresh.classList.add("enabled");
  }

  function stopRefresh() {
    clearInterval(state.logsRefreshId);
  }

  function refreshLogs(state, els) {
    if (document.hidden || !navigator.onLine) return;
    progressx.show(theme);
    state.worker.port.postMessage({ url: "/logs" });
    updateInterval(state, els);
    addFilterInput(els);
    progressx.hide();
  }

  function updateInterval(state, els) {
    state.intervalValue = localStorage.getItem("logsRefresh") || "30";

    if (els.refreshInput) {
      els.refreshInput.min = 0;
      els.refreshInput.max = 3600;
      els.refreshInput.value = state.intervalValue;
    }

    if (els.refreshInput && !els.refreshSpan.classList.contains("listening")) {
      els.refreshInput.addEventListener("input", () => {
        els.refreshSpan.classList.add("listening");
        const value = els.refreshInput.value;
        if (!value || isNaN(value)) return;

        state.intervalValue = value;
        localStorage.setItem("logsRefresh", value);

        clearInterval(state.logsRefreshId);

        if (value === "0") {
          els.toggleRefresh.classList.replace("enabled", "disabled");
        } else {
          state.logsRefreshId = setInterval(() => refreshLogs(state, els), value * 1000);
          els.toggleRefresh.classList.replace("disabled", "enabled");
        }
      });
    }
  }

  function addFilterInput(els) {
    if (!els.routerlogs || els.filterInput._listenerAdded) return;

    const debounce = (fn, delay) => {
      let timeout;
      return (...args) => {
        clearTimeout(timeout);
        timeout = setTimeout(() => fn(...args), delay);
      };
    };

    els.filterInput.addEventListener("input", debounce(() => applyFilter(els), 300));
    els.filterInput._listenerAdded = true;
  }

  function applyFilter(els) {
    const filterValue = els.filterInput.value.toLowerCase();
    if (els.routerlogsList) {
      els.routerlogsList.querySelectorAll("li").forEach((li) => {
        li.style.display = li.textContent.toLowerCase().includes(filterValue) ? "block" : "none";
      });
    }
  }

  function linkifyPattern(list, pattern, linkFormatter) {
    if (!list) return;
    const items = list.querySelectorAll("li");
    items.forEach((li) => {
      let html = li.innerHTML;
      const matches = [...html.matchAll(pattern)]; // Use matchAll to get full matches
      if (!matches) return;

      matches.forEach((match) => {
        const fullMatch = match[0]; // Full matched string (e.g., "key [xxxx]")
        const linkText = fullMatch.replace(/$$|$$/g, "").trim(); // Extract just the ID
        const matchIndex = html.indexOf(fullMatch);
        const preceding = html.slice(0, matchIndex).slice(-"floodfill ".length);
        const isFloodfill = preceding === "floodfill ";
        const link = `<a href="${linkFormatter(linkText)}"${isFloodfill ? ' class="isFF"' : ""}>${linkText}`;

        html = html.replace(fullMatch, link);
      });

      li.innerHTML = html;
    });
  }

  function linkifyRouterIds(list) {
    linkifyPattern(list, /\[([a-zA-Z0-9\~\-]{6})\]/g, (id) => `/netdb?r=${id}`);
  }

  function linkifyLeaseSets(list) {
    linkifyPattern(list, /(?:key\s*)?$([a-zA-Z0-9\~\-]{8})$|(#ls_[a-zA-Z0-9]{4})\b/g, (id) => {
      return `/netdb?l=3#${id.substring(0, 4)}`;
    });
  }

  function linkifyIPv4(list) {
    if (!list) return;
    const ipRegex = /\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b/g;
    linkifyTextNodes(list, ipRegex, (ip) => `<a href="/netdb?ip=${ip}">${ip}</a>`);
  }

  function linkifyIPv6(list) {
    if (!list) return;
    const ipRegex = /\b(?:([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|(?:[0-9a-fA-F]{1,4}:){1,7}:)(?:%[0-9a-zA-Z]{1,})?\b/gi;
    linkifyTextNodes(list, ipRegex, (ip) => {
      const cleanIP = ip.split("%")[0];
      return `<a href="/netdb?ipv6=${cleanIP}">${ip}</a>`;
    });
  }

  function linkifyTextNodes(container, pattern, linkFormatter) {
    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null, false);
    const nodesToReplace = [];

    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (pattern.test(node.textContent)) {
        nodesToReplace.push(node);
      }
    }

    nodesToReplace.forEach((textNode) => {
      const parent = textNode.parentNode;
      const tempDiv = document.createElement("div");
      const html = textNode.textContent.replace(pattern, linkFormatter);
      tempDiv.innerHTML = html;

      while (tempDiv.firstChild) {
        parent.insertBefore(tempDiv.firstChild, textNode);
      }
      parent.removeChild(textNode);
    });
  }

  function tagFirstLi(list) {
    if (!list) return;
    const firstLi = list.querySelector("li");
    if (firstLi) {
      const shortKey = Date.now().toString().slice(-4);
      firstLi.setAttribute("data-key", `log-${shortKey}`);
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    onVisible(els.mainLogs, () => requestAnimationFrame(() => initRefresh()));
    onHidden(els.mainLogs, stopRefresh);
    updateInterval(state, els);
    addFilterInput(els);
    applyFilter(els);

    els.toggleRefresh.addEventListener("click", () => {
      const isOn = els.toggleRefresh.classList.contains("enabled");
      if (isOn) {
        els.toggleRefresh.classList.replace("enabled", "disabled");
        stopRefresh();
      } else {
        els.toggleRefresh.classList.replace("disabled", "enabled");
        refreshLogs(state, els);
        initRefresh();
      }
    });
  });
}

start();