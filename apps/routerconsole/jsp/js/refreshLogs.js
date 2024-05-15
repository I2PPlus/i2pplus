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
    let filterValue = filterInput.value.toLowerCase();
    if (storedFilterValue) {filterValue = storedFilterValue;}

    xhrlogs.open("GET", "/logs", true);
    xhrlogs.responseType = "document";
    xhrlogs.onload = function () {
      if (!xhrlogs.responseXML) {return;}
      const mainLogsResponse = xhrlogs.responseXML.getElementById("logs");
      progressx.show(theme);
      progressx.progress(0.3);

      if (!xhrlogs.responseXML) {return;}

      if (criticallogs) {
        const criticallogsResponse = xhrlogs.responseXML.getElementById("criticallogs");
        if (criticallogs && criticallogsResponse) {
          if (criticallogsResponse.innerHTML !== criticallogs.innerHTML) {
            criticallogs.innerHTML = criticallogsResponse.innerHTML;
          }
        } else if (!criticallogs && criticallogsResponse) {
          mainLogs.innerHTML = mainLogsResponse.innerHTML;
        } else {
          critLogsHead.remove();
          criticallogs.remove();
        }
      }
      if (routerlogsList) {
        const routerlogsListResponse = xhrlogs.responseXML.querySelector("#routerlogs td ul");
        const routerlogsFileInfoResponse = xhrlogs.responseXML.querySelector("#routerlogs tr:first-child td p");
        if (routerlogsListResponse) {
          if (routerlogsList.innerHTML !== routerlogsListResponse.innerHTML) {
            routerlogsList.innerHTML = routerlogsListResponse.innerHTML;
          }
          if (routerlogsFileInfo.innerHTML !== routerlogsFileInfoResponse.innerHTML) {
            routerlogsFileInfo.innerHTML = routerlogsFileInfoResponse.innerHTML;
          }
        }
        linkifyRouterIds();
        linkifyIPv4();
        linkifyIPv6();
      }
      if (servicelogs) {
        const servicelogsResponse = xhrlogs.responseXML.getElementById("wrapperlogs");
        if (servicelogs && servicelogsResponse) {
          if (servicelogsResponse.innerHTML !== servicelogs.innerHTML) {
            servicelogs.innerHTML = servicelogsResponse.innerHTML;
          }
        }
      }
      const liElements = routerlogsList.querySelectorAll("li");
      liElements.forEach((li) => {
        const text = li.textContent;
        if (text.toLowerCase().indexOf(filterValue) !== -1) {li.style.display = "block";}
        else {li.style.display = "none";}
      });
      progressx.hide();
    };
    xhrlogs.send();
    updateInterval();
    addFilterInput();
  }

  function updateInterval() {
    refreshInput.title = "Refresh interval (seconds)";
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

  function linkifyRouterIds() {
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
          li.innerHTML = li.innerHTML.replace(new RegExp(`\\[${linkText}\\]`, "g"), link.outerHTML);
        });
      }
    });
  }

  function linkifyIPv4() {
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const text = li.textContent;
      const ipv4Regex = /\b(?:\/?)(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b/g;
      const matches = text.match(ipv4Regex);

      if (matches) {
        matches.forEach((match) => {
          var linkText = match;
          linkText = match.replace(/^\/+/, ""); // Remove leading slash
          const linkHref = `/netdb?ip=${linkText}`;
          const link = document.createElement("a");
          link.href = linkHref;
          link.textContent = linkText;
          li.innerHTML = li.innerHTML.replace(new RegExp(`\\b${linkText}\\b`, "g"), link.outerHTML);
        });
      }
    });
  }

  function linkifyIPv6() {
    const liElements = routerlogsList.querySelectorAll("li");

    liElements.forEach((li) => {
      const text = li.textContent;
      const ipv6Regex = /\b(?:\/?)(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b/g;
      const matches = text.match(ipv6Regex);

      if (matches) {
        matches.forEach((match) => {
          var linkText = match;
          linkText = match.replace(/^\/+/, ""); // Remove leading slash
          const linkHref = `/netdb?ipv6=${linkText}`;
          const link = document.createElement("a");
          link.href = linkHref;
          link.textContent = linkText;
          li.innerHTML = li.innerHTML.replace(new RegExp(`\\b${linkText}\\b`, "g"), link.outerHTML);
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
        const liElements = routerlogsList.querySelectorAll("li");
        filterSpan.classList.add("listening");

        liElements.forEach((li) => {
          const text = li.textContent;
          if (text.toLowerCase().indexOf(filterValue) !== -1) {
            li.style.display = "block";
          } else {
            li.style.display = "none";
          }
        });
      });
    }
    // Store the filter value in local storage
    const storedFilterValue = localStorage.getItem("logFilter");
    if (storedFilterValue) {filterInput.value = storedFilterValue;}
  }

  document.addEventListener("DOMContentLoaded", function() {
    linkifyRouterIds();
    linkifyIPv4();
    linkifyIPv6();
    onVisible(mainLogs, initRefresh);
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