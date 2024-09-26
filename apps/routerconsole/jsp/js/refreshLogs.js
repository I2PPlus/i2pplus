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
  const noCritLogs = document.querySelector("#criticallogs .nologs");
  const routerlogs = document.getElementById("routerlogs");
  const routerlogsFileInfo = document.querySelector("#routerlogs tr:first-child td p");
  const routerlogsList = document.querySelector("#routerlogs td ul");
  const servicelogs = document.getElementById("wrapperlogs");
  const refreshSpan = document.getElementById("refreshPeriod");
  const refreshInput = document.getElementById("logRefreshInterval");
  const refreshValue = localStorage.getItem("logsRefresh") || "30";
  const toggleRefresh = document.getElementById("toggleRefresh");
  const visible = document.visibilityState;
  const xhrlogs = new XMLHttpRequest();
  let logsRefreshId;
  let intervalValue;

  function initRefresh() {
    if (!mainLogs || !routerlogs) {
      setTimeout(initRefresh, 500);
      return;
    }
    stopRefresh();
    logsRefreshId = setInterval(refreshLogs, refreshValue*1000);
    if (!toggleRefresh.classList.contains("enabled")) {
      toggleRefresh.classList.add("enabled");
    }
  }

  function stopRefresh() {
    if (logsRefreshId) {clearInterval(logsRefreshId);}
  }

  function refreshLogs() {
    const storedFilterValue = localStorage.getItem("logFilter");
    const filterInput = document.getElementById("logFilterInput");
    let filterValue = encodeURIComponent(filterInput.value.trim().toLowerCase()).replace(/%20/g, " ");
    if (storedFilterValue) {filterValue = storedFilterValue;}

    xhrlogs.open("GET", "/logs", true);
    xhrlogs.responseType = "document";
    xhrlogs.onload = function () {
      if (!xhrlogs.responseXML) {return;}
      const mainLogsResponse = xhrlogs.responseXML.getElementById("logs");
      const criticallogsResponse = criticallogs !== null ? xhrlogs.responseXML.getElementById("criticallogs") : null;
      progressx.show(theme);
      progressx.progress(0.3);

      if (!criticallogs && criticallogsResponse) {
          mainLogs.innerHTML = mainLogsResponse.innerHTML;
      } else if (criticallogs && criticallogsResponse) {
          if (criticallogsResponse.innerHTML !== criticallogs.innerHTML) {
            criticallogs.innerHTML = criticallogsResponse.innerHTML;
          }
      } else {
        critLogsHead?.remove();
        criticallogs?.remove();
      }

      if (routerlogsList) {
        const routerlogsListResponse = xhrlogs.responseXML.querySelector("#routerlogs td ul");
        const routerlogsFileInfoResponse = xhrlogs.responseXML.querySelector("#routerlogs tr:first-child td p");
        if (routerlogsList && routerlogsListResponse) {
          if (routerlogsList.innerHTML !== routerlogsListResponse.innerHTML) {
            routerlogsList.innerHTML = routerlogsListResponse.innerHTML;
          }
          if (routerlogsFileInfo.innerHTML !== routerlogsFileInfoResponse.innerHTML) {
            routerlogsFileInfo.innerHTML = routerlogsFileInfoResponse.innerHTML;
          }
        }
      } else if (routerlogs) {
        const routerlogsTr = routerlogs.querySelector("tr:nth-child(2)");
        const routerlogsTrResponse = xhrlogs.responseXML.querySelector("#routerlogs tr:nth-child(2)");
        if (routerlogsTr && routerlogsTrResponse && routerlogsTr !== routerlogsTrResponse) {
          routerlogsTr.innerHTML = routerlogsTrResponse.innerHTML;
        }
      }
      linkifyRouterIds();
      linkifyLeaseSets();
      linkifyIPv4();
      linkifyIPv6();
      if (servicelogs) {
        const servicelogsResponse = xhrlogs.responseXML.getElementById("wrapperlogs");
        if (servicelogs && servicelogsResponse) {
          if (servicelogsResponse.innerHTML !== servicelogs.innerHTML) {
            servicelogs.innerHTML = servicelogsResponse.innerHTML;
          }
        }
      }
      if (routerlogsList) {
        const liElements = routerlogsList.querySelectorAll("li");
        liElements.forEach((li) => {
          const text = li.textContent;
          if (text.toLowerCase().indexOf(filterValue) !== -1) {li.style.display = "block";}
          else {li.style.display = "none";}
        });
      }
      progressx.hide();
    };
    xhrlogs.send();
    updateInterval();
    addFilterInput();
  }

  function updateInterval() {
    refreshInput.min = 0;
    refreshInput.max = 3600;
    intervalValue = localStorage.getItem("logsRefresh") || "30"; // default value in seconds
    refreshInput.value = intervalValue;

    if (!refreshSpan.classList.contains("listening")) {
      refreshInput.addEventListener("input", () => {
        refreshSpan.classList.add("listening");
        intervalValue = refreshInput.value; // update intervalValue with input value
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
    }
  }

  function escapeRegExp(string) {
    return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); // Escape special characters
  }

  function linkifyRouterIds() {
    if (!routerlogsList) { return; }
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const text = li.textContent;
      const matches = text.match(/\[([a-zA-Z0-9\~\-]{6})\]/g);

      if (matches) {
        matches.forEach((match) => {
          const linkText = match.substring(1, match.length - 1); // remove the square brackets
          const linkHref = `/netdb?r=${linkText}`;
          const link = document.createElement("a");
          link.href = linkHref;
          link.textContent = linkText;

          // Escape the linkText to ensure it is safe for the regex
          const escapedLinkText = escapeRegExp(linkText);
          li.innerHTML = li.innerHTML.replace(new RegExp(`\\[${escapedLinkText}\\]`, "g"), link.outerHTML);
        });
      }
    });
  }

  function linkifyLeaseSets() {
    if (!routerlogsList) { return; }
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const text = li.textContent;
      // The regex should match a possible prefix of "key " and then the brackets
      const matches = text.match(/(?:key\s*)?\[([a-zA-Z0-9\~\-]{8})\]/g);

      if (matches) {
        matches.forEach((match) => {
          // Capture the link text correctly, ignoring any prefix matches
          const linkedTextOnly = match.match(/([a-zA-Z0-9\~\-]{8})/)[0];
          const linkHref = `/netdb?l=3#ls_${linkedTextOnly.substring(0,4)}`;
          const link = document.createElement("a");
          link.href = linkHref;
          link.textContent = linkedTextOnly;

          // Escape the link text to ensure it is safe for the regex
          const escapedLinkText = escapeRegExp(linkedTextOnly);
          li.innerHTML = li.innerHTML.replace(new RegExp(`(?:key\\s*)?\\[${escapedLinkText}\\]`, "g"), link.outerHTML);
        });
      }
    });
  }

  function linkifyIPv4() {
    if (!routerlogsList) { return; }
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const text = li.textContent;
      const ipv4Regex = /(?<!\bAddress:\s*)\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b/g;
      const matches = text.match(ipv4Regex);

      if (matches) {
        matches.forEach((match) => {
          const linkText = match.trim();
          if (!li.querySelector(`a[href*="${linkText}"]`)) {
            const linkHref = `/netdb?ip=${linkText}`;
            const link = document.createElement("a");
            link.href = linkHref;
            link.textContent = linkText;
            li.innerHTML = li.innerHTML.replace(new RegExp(`\\b${escapeRegExp(linkText)}\\b`, "g"), ` ${link.outerHTML}`);
          }
        });
      }
    });
  }

  function linkifyIPv6() {
    if (!routerlogsList) {return;}
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const text = li.textContent;
      const ipv6Regex = /\b(?:(?<!\bAddress:)\s*|\baddress:\s*)([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b/gi;
      const matches = text.match(ipv6Regex);

      if (matches) {
        matches.forEach((match) => {
          const linkText = match;
          if (!li.querySelector(`a[href*="${linkText}"]`)) {
            const linkHref = `/netdb?ipv6=${linkText}`;
            const link = document.createElement("a");
            link.href = linkHref;
            link.textContent = linkText;
            li.innerHTML = li.innerHTML.replace(new RegExp(`\\b${linkText}\\b`, "g"), ` ${link.outerHTML}`);
          }
        });
      }
    });
  }

  function addFilterInput() {
    const filterSpan = document.getElementById("logFilter");
    const filterInput = document.getElementById("logFilterInput");

    if (!filterSpan.classList.contains("listening")) {
      filterInput.addEventListener("input", () => {
        const filterValue = filterInput.value.toLowerCase();
        filterSpan.classList.add("listening");
        if (routerlogsList) {
          const liElements = routerlogsList.querySelectorAll("li");
          liElements.forEach((li) => {
            const text = li.textContent;
            if (text.toLowerCase().indexOf(filterValue) !== -1) {li.style.display = "block";}
            else {li.style.display = "none";}
          });
        }
      });
    }
    // Store the filter value in local storage
    const storedFilterValue = localStorage.getItem("logFilter");
    if (storedFilterValue) {filterInput.value = storedFilterValue;}
  }

  document.addEventListener("DOMContentLoaded", function() {
    linkifyRouterIds();
    linkifyLeaseSets();
    linkifyIPv4();
    linkifyIPv6();
    if ("requestIdleCallback" in window) {
      onVisible(mainLogs, () => {
        window.requestIdleCallback(initRefresh);
      });
    } else {
      onVisible(mainLogs, () => {
        window.requestAnimationFrame(initRefresh);
      });
    }
    onHidden(mainLogs, stopRefresh);
    updateInterval();
    addFilterInput();

    document.addEventListener("click", function(event) {
      if (event.target === toggleRefresh) {
        let isRefreshOn = toggleRefresh.classList.contains("enabled");
        if (isRefreshOn) {
          toggleRefresh.classList.remove("enabled");
          toggleRefresh.classList.add("disabled");
          stopRefresh();
        } else {
          toggleRefresh.classList.remove("disabled");
          toggleRefresh.classList.add("enabled");
          if (intervalValue === 0) {
            intervalValue = 30;
            localStorage.setItem("logsRefresh", "30");
          }
          refreshLogs();
          initRefresh();
        }
      }
    });
  });
}

start();