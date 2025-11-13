/* I2P+ jobs.js by dr|z3d */
/* Handle refresh, table sorts on /jobs pages */
/* License: AGPL3 or later */

(function() {
  const jobs = document.getElementById("jobstats");
  const sorter = new Tablesort(jobs, { descending: true });
  const xhrjobs = new XMLHttpRequest();
  const REFRESH_INTERVAL = 5 * 1000;

  let refreshInterval = null;

  function startRefresh() {
    refreshInterval = setInterval(function() {
      xhrjobs.open("GET", "/jobs", true);
      xhrjobs.responseType = "document";
      xhrjobs.onload = function() {
        if (!xhrjobs.responseXML) return;

        const jobsResponse = xhrjobs.responseXML.getElementById("jobstats");
        const rows = document.querySelectorAll("#statCount tr");
        const rowsResponse = xhrjobs.responseXML.querySelectorAll("#statCount tr");
        const tbody = document.getElementById("statCount");
        const tbodyResponse = xhrjobs.responseXML.getElementById("statCount");
        const tfoot = document.getElementById("statTotals");
        const tfootResponse = xhrjobs.responseXML.getElementById("statTotals");
        const updatingTds = document.querySelectorAll("#statCount td");
        const updatingTdsResponse = xhrjobs.responseXML.querySelectorAll("#statCount td");

        let updated = false;

        if (!Object.is(jobs.innerHTML, jobsResponse.innerHTML)) {
          if (rows.length !== rowsResponse.length) {
            tbody.innerHTML = tbodyResponse.innerHTML;
            tfoot.innerHTML = tfootResponse.innerHTML;
            updated = true;
          } else {
            Array.from(updatingTds).forEach((elem, index) => {
              const newElem = updatingTdsResponse[index];
              if (elem.innerHTML.trim() !== newElem.innerHTML.trim()) {
                elem.innerHTML = newElem.innerHTML;
                elem.classList.add("updated");
                updated = true;
              } else {
                elem.classList.remove("updated");
              }
            });

            if (tfoot.innerHTML !== tfootResponse.innerHTML) {
              tfoot.innerHTML = tfootResponse.innerHTML;
            }
          }

          sorter.refresh();
        }
      };

      xhrjobs.send();
    }, REFRESH_INTERVAL);
  }

  function stopRefresh() {
    if (refreshInterval) {
      clearInterval(refreshInterval);
      refreshInterval = null;
    }
  }

  if (document.visibilityState === "visible") {
    startRefresh();
  }

  document.addEventListener("visibilitychange", function() {
    if (document.visibilityState === "visible") {
      startRefresh();
    } else {
      stopRefresh();
    }
  });

  jobs.addEventListener("beforeSort", function() {
    progressx.show(theme);
    progressx.progress(0.5);
  });

  jobs.addEventListener("afterSort", function() {
    progressx.hide();
  });
})();