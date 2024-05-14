/* I2P+ refreshLogs.js by dr|z3d */
/* Refreshes Router Console /logs and enables refresh period configuration */
/* License: AGPL3 or later */

import {onVisible, onHidden} from "/js/onVisible.js";

function start() {
  const mainLogs = document.getElementById("logs");
  const criticallogs = document.getElementById("criticallogs");
  const critLogsHead = document.getElementById("critLogsHead");
  const noCritLogs = document.querySelector("#criticallogs .nologs");
  const routerlogs = document.getElementById("routerlogs");
  const servicelogs = document.getElementById("wrapperlogs");
  const toggleRefresh = document.getElementById("toggleRefresh");
  const visible = document.visibilityState;
  const xhrlogs = new XMLHttpRequest();
  let refreshId;

  function initRefresh() {
    if (!mainLogs || !routerlogs) {
      setTimeout(initRefresh, 500);
      return;
    }
    stopRefresh();
    refreshId = setInterval(refreshLogs, 5000);
    if (!toggleRefresh.classList.contains("enabled")) {
      toggleRefresh.classList.add("enabled");
    }
  }

  function stopRefresh() {
    if (refreshId) {clearInterval(refreshId);}
  }

  function refreshLogs() {
    xhrlogs.open("GET", "/logs", true);
    xhrlogs.responseType = "document";
    xhrlogs.onload = function () {
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
      if (routerlogs) {
        const routerlogsResponse = xhrlogs.responseXML.getElementById("routerlogs");
        if (routerlogs && routerlogsResponse) {
          if (routerlogsResponse.innerHTML !== routerlogs.innerHTML) {
            routerlogs.innerHTML = routerlogsResponse.innerHTML;
          }
          linkifyRouterIds();
        }
      }
      if (servicelogs) {
        const servicelogsResponse = xhrlogs.responseXML.getElementById("wrapperlogs");
        if (servicelogs && servicelogsResponse) {
          if (servicelogsResponse.innerHTML !== servicelogs.innerHTML) {
            servicelogs.innerHTML = servicelogsResponse.innerHTML;
          }
        }
      }
      progressx.hide();
    }
    xhrlogs.send();
  }

  function updateInterval() {
    const refreshSpan = document.getElementById("refreshPeriod");
    const intervalInput = document.createElement("input");
    refreshSpan.style.display = "none";
    intervalInput.type = "number";
    intervalInput.placeholder = "Override interval (in seconds)";
    intervalInput.min = 0;
    intervalInput.max = 3600;

    refreshSpan.appendChild(intervalInput);

    intervalInput.value = localStorage.getItem("refreshInterval") || "30"; // default value in seconds

    intervalInput.addEventListener("input", () => {
      const intervalValue = parseInt(intervalInput.value);
      if (!isNaN(intervalValue)) {
        if (intervalValue === 0) {
          clearInterval(refreshId);
          localStorage.setItem("refreshInterval", "0");
          if (toggleRefresh.classList.contains("enabled")) {
            toggleRefresh.classList.remove("enabled");
            toggleRefresh.classList.add("disabled");
          }
        } else {
          clearInterval(refreshId);
          refreshId = setInterval(refreshLogs, intervalValue * 1000); // convert seconds to milliseconds
          localStorage.setItem("refreshInterval", intervalValue.toString());
          if (toggleRefresh.classList.contains("disabled")) {
            toggleRefresh.classList.remove("disabled");
            toggleRefresh.classList.add("enabled");
          }
        }
      }
    });
  }

  function linkifyRouterIds() {
    const liElements = routerlogs.querySelectorAll("li");

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

  document.addEventListener("DOMContentLoaded", function() {
    linkifyRouterIds();
    onVisible(mainLogs, initRefresh);
    onHidden(mainLogs, stopRefresh);
    updateInterval();

    document.addEventListener("click", function(event) {
      if (event.target === toggleRefresh) {
        var isRefreshOn = toggleRefresh.classList.contains("enabled");
        if (isRefreshOn) {
          toggleRefresh.classList.remove("enabled");
          toggleRefresh.classList.add("disabled");
          stopRefresh();
        } else {
          toggleRefresh.classList.remove("disabled");
          toggleRefresh.classList.add("enabled");
          refreshLogs();
          initRefresh();
        }
      }
    });
  });
}

start();