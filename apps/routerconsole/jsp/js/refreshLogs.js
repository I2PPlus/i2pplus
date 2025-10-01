/* I2P+ refreshLogs.js by dr|z3d */
/* Refreshes Router Console /logs, enables refresh period configuration */
/* implements a realtime router logs filter, and linkifies routerid hashes */
/* and ip addresses */
/* License: AGPL3 or later */

import {onVisible, onHidden} from "/js/onVisible.js";

function start() {
  const mainLogs = document.getElementById("logs");
  const criticallogs = document.getElementById("criticallogs");
  const critLogsHead = document.getElementById("critLogsHead");
  const noCritLogs = criticallogs?.querySelector(".nologs");
  const errorCount = document.getElementById("errorCount");
  const routerlogs = document.getElementById("routerlogs");
  const routerlogsList = routerlogs?.querySelector("td ul");
  const routerlogsFileInfo = routerlogs?.querySelector("tr:first-child td p");
  const servicelogs = document.getElementById("wrapperlogs");
  const refreshSpan = document.getElementById("refreshPeriod");
  const refreshInput = document.getElementById("logRefreshInterval");
  const toggleRefresh = document.getElementById("toggleRefresh");
  const updates = [];
  const worker = new SharedWorker("/js/fetchWorker.js");
  const xhrlogs = new XMLHttpRequest();
  let logsRefreshId;
  let intervalValue;

  if ("SharedWorker" in window) {
    worker.port.onmessage = (event) => {
      const { responseText, isDown, noResponse } = event.data;
      if (isDown) {return;}

      const parser = new DOMParser();
      const doc = parser.parseFromString(responseText, "text/html");
      const mainLogsResponse = doc.getElementById("logs");
      const criticallogsResponse = criticallogs ? doc.getElementById("criticallogs") : null;
      const errorCountResponse = errorCount ? doc.getElementById("errorCount") : null;

      if (errorCount && errorCountResponse) {
        if (errorCountResponse.textContent !== errorCount.textContent) {
          updates.push(() => {errorCount.textContent = errorCountResponse.textContent;});
        }
      }

      if (criticallogs && criticallogsResponse) {
        if (criticallogsResponse.innerHTML !== criticallogs.innerHTML) {
          updates.push(() => {criticallogs.innerHTML = criticallogsResponse.innerHTML;});
        }
      }

      if (routerlogs && routerlogsList) {
        const routerlogsListResponse = doc.querySelector("#routerlogs td ul");
        const routerlogsFileInfoResponse = doc.querySelector("#routerlogs tr:first-child td p");
        const fragment = document.createDocumentFragment();
        if (routerlogsListResponse) {
          if (routerlogsList.innerHTML !== routerlogsListResponse.innerHTML) {
            routerlogsList.innerHTML = '';
            routerlogsListResponse.querySelectorAll('li').forEach(li => {
              fragment.appendChild(li.cloneNode(true));
            });
            updates.push(() => {
              routerlogsList.appendChild(fragment);
            });
          }

          if (routerlogsFileInfo.innerHTML !== routerlogsFileInfoResponse.innerHTML) {
            updates.push(() => {
              routerlogsFileInfo.innerHTML = routerlogsFileInfoResponse.innerHTML;
            });
          }
        }
      }

      if (servicelogs) {
        const servicelogsResponse = doc.getElementById("wrapperlogs");
        if (servicelogsResponse && servicelogsResponse.innerHTML !== servicelogs.innerHTML) {
          updates.push(() => {
            servicelogs.innerHTML = servicelogsResponse.innerHTML;
          });
        }
      }

      if (routerlogs && routerlogsList) {
        const liElements = routerlogsList.querySelectorAll("li");
        liElements.forEach(li => {
          li.style.display = li.textContent.toLowerCase().includes(filterValue) ? "block" : "none";
        });
      }
     doUpdates();
    };
    worker.port.start();
    worker.port.postMessage({ url: "/logs" });
  }

  function initRefresh() {
    if (!mainLogs || !routerlogs) {
      setTimeout(initRefresh, 500);
      return;
    }
    stopRefresh();
    logsRefreshId = setInterval(refreshLogs, (localStorage.getItem("logsRefresh") || 30) * 1000);
    toggleRefresh.classList.add("enabled");
  }

  function stopRefresh() {
    clearInterval(logsRefreshId);
  }

  function refreshLogs() {
    if (document.hidden || !navigator.onLine) {return;}
    const filterInput = document.getElementById("logFilterInput");
    const storedFilterValue = localStorage.getItem("logFilter");
    let filterValue = encodeURIComponent(filterInput?.value.trim().toLowerCase()).replace(/%20/g, " ");
    if (storedFilterValue) { filterValue = storedFilterValue; }
    progressx.show(theme);
    worker.port.postMessage({ url: "/logs" });
    updateInterval();
    addFilterInput();
    progressx.hide();
  }

  function updateInterval() {
    intervalValue = localStorage.getItem("logsRefresh") || "30"; // default value in seconds

    if (routerlogs) {
      refreshInput.min = 0;
      refreshInput.max = 3600;
      refreshInput.value = intervalValue;
    }

    if (routerlogs && !refreshSpan.classList.contains("listening")) {
      refreshInput?.addEventListener("input", () => {
        refreshSpan.classList.add("listening");
        intervalValue = refreshInput?.value; // update intervalValue with input value
        if (!Number.isNaN(intervalValue)) {
          if (intervalValue === 0) {
            clearInterval(logsRefreshId);
            localStorage.setItem("logsRefresh", "0");
            if (toggleRefresh.classList.contains("enabled")) {
              toggleRefresh.classList.remove("enabled");
              toggleRefresh.classList.add("disabled");
            }
          } else {
            clearInterval(logsRefreshId);
            logsRefreshId = setInterval(refreshLogs, intervalValue * 1000);
            localStorage.setItem("logsRefresh", intervalValue);
            if (toggleRefresh.classList.contains("disabled")) {
              toggleRefresh.classList.remove("disabled");
              toggleRefresh.classList.add("enabled");
            }
          }
        }
      });
    } else {
      clearInterval(logsRefreshId);
      logsRefreshId = setInterval(refreshLogs, intervalValue * 1000);
    }
  }

  function doUpdates() {
    if (document.hidden || !navigator.onLine) {return;}
    requestAnimationFrame(() => {
      updates.forEach(update => update());
      linkifyRouterIds();
      linkifyLeaseSets();
      linkifyIPv4();
      linkifyIPv6();
      applyFilter();
    });
  }

  function escapeRegExp(string) {
    return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); // Escape special characters
  }

  function linkifyPattern(list, pattern, linkFormatter) {
    const liElements = list.querySelectorAll("li");

    liElements.forEach((li) => {
      let newHTML = li.innerHTML;
      const matches = li.textContent.match(pattern);

      if (matches) {
        matches.forEach((match) => {const linkText = match.replace(/\[|\]/g, ""); // Remove square brackets
        // Check if the match is preceded by "floodfill "
        const precedingText = newHTML.slice(0, newHTML.indexOf(match)).slice(-"floodfill ".length);
        const isFloodfill = precedingText === "floodfill ";
        const linkHref = linkFormatter(linkText);
        const classAttr = isFloodfill ? ' class="isFF"' : '';
        if (isFloodfill) {newHTML = newHTML.replace("floodfill " + match, match);}
        newHTML = newHTML.replace(match, `<a href="${linkHref}"${classAttr}>${linkText}</a>`);
        });
        li.innerHTML = newHTML; // Update only once
      }
    });
  }

  function linkifyRouterIds() {
    if (!routerlogsList) return;
    const pattern = /\[([a-zA-Z0-9\~\-]{6})\]/g;
    linkifyPattern(routerlogsList, pattern, (linkText) => `/netdb?r=${linkText}`);
  }

  function linkifyLeaseSets() {
    if (!routerlogsList) return;
    const pattern = /(?:key\s*)?\[([a-zA-Z0-9\~\-]{8})\]/g;
    linkifyPattern(routerlogsList, pattern, (linkText) => `/netdb?l=3#ls_${linkText.substring(0, 4)}`);
  }

  function linkifyIPv4() {
    if (!routerlogsList) return;

    const ipRegex = /\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b/g;
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const walker = document.createTreeWalker(li, NodeFilter.SHOW_TEXT, null, false);
      const nodesToReplace = [];

      while (walker.nextNode()) {
        const node = walker.currentNode;
        if (ipRegex.test(node.textContent)) {
          nodesToReplace.push(node);
        }
      }

      nodesToReplace.forEach((textNode) => {
        const parentNode = textNode.parentNode;
        const tempDiv = document.createElement("div");
        const html = textNode.textContent.replace(ipRegex, (ip) => {
          return ` <a href="/netdb?ip=${ip}">${ip}</a>`;
        });
        tempDiv.innerHTML = html;

        // Replace the textNode with the generated nodes
        while (tempDiv.firstChild) {
          parentNode.insertBefore(tempDiv.firstChild, textNode);
        }
        parentNode.removeChild(textNode);
      });
    });
  }

  function linkifyIPv6() {
    if (!routerlogsList) return;

    // Regex to match IPv6 addresses, including optional zone index
    const ipRegex = /\b(?:([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|(?:[0-9a-fA-F]{1,4}:){1,7}:)(?:%[0-9a-zA-Z]{1,})?\b/gi;
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const walker = document.createTreeWalker(li, NodeFilter.SHOW_TEXT, null, false);
      const nodesToReplace = [];

      while (walker.nextNode()) {
        const node = walker.currentNode;
        if (ipRegex.test(node.textContent)) {
          nodesToReplace.push(node);
        }
      }

      nodesToReplace.forEach((textNode) => {
        const parentNode = textNode.parentNode;
        const tempDiv = document.createElement("div");
        const html = textNode.textContent.replace(ipRegex, (ip) => {
          // Strip zone index for link, optional: keep it in display
          const cleanIP = ip.split("%")[0]; // remove zone for netdb lookup
          return ` <a href="/netdb?ipv6=${cleanIP}">${ip}</a>`;
        });
        tempDiv.innerHTML = html;

        // Replace the textNode with the generated nodes
        while (tempDiv.firstChild) {
          parentNode.insertBefore(tempDiv.firstChild, textNode);
        }
        parentNode.removeChild(textNode);
      });
    });
  }

  const filterInput = document.getElementById("logFilterInput");
  let filterListener = false;

  function addFilterInput() {
    if (!routerlogs) return;
    const filterSpan = document.getElementById("logFilter");
    const debounce = (func, delay) => {
      let timeoutId;
      return function (...args) {
        if (timeoutId) clearTimeout(timeoutId);
        timeoutId = setTimeout(() => {
          func.apply(null, args);
        }, delay);
      };
    };
    if (!filterListener) {filterInput.addEventListener("input", debounce(applyFilter, 300));}
    filterListener = true;
  }

  function applyFilter() {
    if (!routerlogs) return;
    const filterValue = filterInput.value.toLowerCase();
    if (routerlogsList) {
      const liElements = routerlogsList.querySelectorAll("li");
      liElements.forEach((li) => {
        const text = li.textContent;
        li.style.display = text.toLowerCase().includes(filterValue) ? "block" : "none";
      });
    }
  }

  document.addEventListener("DOMContentLoaded", function() {
    doUpdates();
    onVisible(mainLogs, () => { requestAnimationFrame(initRefresh); });
    onHidden(mainLogs, stopRefresh);
    updateInterval();
    addFilterInput();
    applyFilter();

    document.addEventListener("click", function(event) {
      if (event.target === toggleRefresh) {
        const isRefreshOn = toggleRefresh.classList.contains("enabled");
        if (isRefreshOn) {
          toggleRefresh.classList.remove("enabled");
          toggleRefresh.classList.add("disabled");
          stopRefresh();
        } else {
          toggleRefresh.classList.remove("disabled");
          toggleRefresh.classList.add("enabled");
          intervalValue = localStorage.getItem("logsRefresh") || "30";
          refreshLogs();
          initRefresh();
        }
      }
    });
  });
}

start();