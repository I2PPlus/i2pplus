import { onVisible, onHidden } from "/js/onVisible.js";
import morphdom from "/js/morphdom.js";

function start() {
  const $ = id => document.getElementById(id);

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
    filterInput: $("logFilterInput")
  };

  const state = {
    updates: [],
    worker: new SharedWorker("/js/fetchWorker.js"),
    logsRefreshId: null,
    intervalValue: localStorage.getItem("logsRefresh") || "30"
  };

  if ("SharedWorker" in window) {
    state.worker.port.onmessage = event => {
      const { responseText, isDown } = event.data;
      if (isDown) return;

      const parser = new DOMParser();
      const doc = parser.parseFromString(responseText, "text/html");

      processUpdates(doc, els, state);
      requestAnimationFrame(() => {
        state.updates.forEach(fn => fn());
        applyFilter(els);
        state.updates = [];
      });
    };

    state.worker.port.start();
    state.worker.port.postMessage({ url: "/logs" });
  }

  function processUpdates(doc, els, state) {
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
        const clone = newList.cloneNode(true);
        tagFirstLi(clone);
        linkifyLogEntries(clone);
        state.updates.push(() => morphdom(els.routerlogsList, clone, {
          childrenOnly: true,
          getNodeKey: el => el.nodeType === Node.ELEMENT_NODE ? el.getAttribute("data-key") : null
        }));
      }
    }

    if (els.servicelogs) {
      const newEl = doc.getElementById("wrapperlogs");
      if (newEl) state.updates.push(() => morphdom(els.servicelogs, newEl, { childrenOnly: true }));
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
      els.routerlogsList.querySelectorAll("li").forEach(li => {
        li.style.display = li.textContent.toLowerCase().includes(filterValue) ? "" : "none";
      });
    }
  }

  function linkifyLogEntries(container) {
    if (!container) return;

    const isValidPort = s => {
      const n = +s;
      return n >= 1 && n <= 65535 && s === String(n);
    };

    const routerPattern = /\[([a-zA-Z0-9~\-]{6})\]/g;
    const leasePattern = /(?:key\s*)?\$([a-zA-Z0-9~\-]{8})\$|#ls_([a-zA-Z0-9]{4})\b|\[([a-zA-Z0-9~\-]{8})\]/g;
    const ipv4PortPattern = /\b((?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b(?::(\d{1,5}))?\b/g;
    const ipv6PortPattern = /(?:\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|\b(?:[0-9a-fA-F]{1,4}:){1,7}:)(?::(\d{1,5}))?\b/gi;

    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        return node.parentElement?.tagName === "A" ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    }, false);

    const textNodes = [];
    let node;
    while ((node = walker.nextNode())) {
      textNodes.push(node);
    }

    textNodes.forEach(textNode => {
      const parent = textNode.parentElement;
      if (!parent) return;

      const text = textNode.textContent;
      const fragments = [];
      let cursor = 0;
      const matches = [];

      const addMatches = (pattern, getMatches) => {
        let match;
        pattern.lastIndex = 0;
        while ((match = pattern.exec(text)) !== null) {
          const results = getMatches(match);
          if (results) matches.push(...results);
        }
      };

      addMatches(/floodfill\s*\[([a-zA-Z0-9~\-]{6})\]/gi, m => [{
        start: m.index,
        end: m.index + m[0].length,
        clean: {
          id: m[1],
          href: `/netdb?r=${m[1]}`,
          isFF: true
        }
      }]);

      addMatches(/\[([a-zA-Z0-9~\-]{6})\]/g, m => [{
        start: m.index,
        end: m.index + m[0].length,
        clean: {
          id: m[1],
          href: `/netdb?r=${m[1]}`,
          isFF: false
        }
      }]);

      addMatches(leasePattern, m => {
        const id = m[1] || m[2] || m[3];
        if (!id) return [];
        return [{ start: m.index, end: m.index + m[0].length, clean: { id, href: `/netdb?l=3#${id.substring(0, 4)}` } }];
      });

      addMatches(ipv4PortPattern, m => {
        const ipStart = m.index;
        const full = m[0];
        const colonIdx = full.indexOf(':');
        const ip = colonIdx === -1 ? full : full.substring(0, colonIdx);
        const ipEnd = ipStart + ip.length;
        const results = [{ start: ipStart, end: ipEnd, clean: { id: ip, href: `/netdb?ip=${ip}` } }];
        if (colonIdx !== -1) {
          const portStr = m[2];
          if (portStr && isValidPort(portStr)) {
            const portStart = ipEnd + 1;
            results.push({ start: portStart, end: portStart + portStr.length, clean: { id: portStr, href: `/netdb?port=${portStr}` } });
          }
        }
        return results;
      });

      addMatches(ipv6PortPattern, m => {
        const full = m[0];
        const ipStart = m.index;
        const portStr = m[2];
        const ipEnd = portStr ? ipStart + (full.length - portStr.length - 1) : ipStart + full.length;
        const ipRaw = full.substring(0, ipEnd - ipStart);
        const cleanIP = ipRaw.split('%')[0];
        const results = [{ start: ipStart, end: ipEnd, clean: { id: ipRaw, href: `/netdb?ipv6=${cleanIP}` } }];
        if (portStr && isValidPort(portStr)) {
          const portStart = ipEnd + 1;
          results.push({ start: portStart, end: portStart + portStr.length, clean: { id: portStr, href: `/netdb?port=${portStr}` } });
        }
        return results;
      });

      if (matches.length === 0) return;
      matches.sort((a, b) => a.start - b.start);

      const merged = [];
      for (const m of matches) {
        if (merged.length === 0 || m.start >= merged[merged.length - 1].end) {
          merged.push(m);
        }
      }

      for (const match of merged) {
        if (match.start > cursor) {
          fragments.push(document.createTextNode(text.slice(cursor, match.start)));
        }

        const link = document.createElement("a");
        link.href = match.clean.href;
        link.textContent = match.clean.id;
        if (match.clean.isFF) {link.classList.add("isFF");}
        fragments.push(link);
        cursor = match.end;
      }

      if (cursor < text.length) {
        fragments.push(document.createTextNode(text.slice(cursor)));
      }

      fragments.forEach(frag => parent.insertBefore(frag, textNode));
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