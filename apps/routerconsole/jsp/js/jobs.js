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
    refreshInterval = setInterval(() => {
      xhrjobs.open("GET", "/jobs", true);
      xhrjobs.responseType = "document";
      xhrjobs.onload = () => {
        if (!xhrjobs.responseXML) return;

        const jobsResponse = xhrjobs.responseXML.getElementById("jobstats");
        if (!jobsResponse) return;


        const oldTbody = jobs.querySelector("#statCount");
        const oldTfoot = jobs.querySelector("#statTotals");
        const newTbody = jobsResponse.querySelector("#statCount");
        const newTfoot = jobsResponse.querySelector("#statTotals");

        progressx.show(theme);
        requestAnimationFrame(() => {

          if (newTbody && oldTbody) {
            const oldRows = Array.from(oldTbody.rows);
            const newRows = Array.from(newTbody.rows);

            newRows.forEach((newRow) => {
              const jobName = newRow.cells[0]?.textContent.trim();
              if (!jobName) return;

              const oldRow = Array.from(oldTbody.rows).find(r => r.cells[0]?.textContent.trim() === jobName);
              if (!oldRow) {
                oldTbody.appendChild(newRow.cloneNode(true));
                return;
              }

              const oldCells = Array.from(oldRow.cells);
              const newCells = Array.from(newRow.cells);

              newCells.forEach((newCell, j) => {
                const oldCell = oldCells[j];
                if (!oldCell) return;

                const oldSpan = oldCell.querySelector("span");
                const newSpan = newCell.querySelector("span");

                const oldText = (oldSpan ? oldSpan.textContent : oldCell.textContent).trim();
                const newText = (newSpan ? newSpan.textContent : newCell.textContent).trim();

                if (oldText !== newText) {
                  newCell.classList.add("updated");
                }

                setTimeout(() => newCell.classList.remove("updated"), REFRESH_INTERVAL - 500);
              });

              oldRow.replaceWith(newRow);
            });

          }
          sorter.refresh();
        });
        setTimeout(() => {progressx.hide();}, 500);
      };

      xhrjobs.send();
    }, REFRESH_INTERVAL);
  }

  function stopRefresh() {
    if (refreshInterval) clearInterval(refreshInterval);
    refreshInterval = null;
  }

  jobs.addEventListener("beforeSort", () => {
    progressx.show(theme);
  });

  jobs.addEventListener("afterSort", () => {
    progressx.progress(0.5);
    setTimeout(() => {progressx.hide();}, 500);
  });

  if (document.visibilityState === "visible") {
    startRefresh();
  }

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      startRefresh();
    } else {
      stopRefresh();
    }
  });

  requestAnimationFrame(() => {sorter.refresh();});

})();