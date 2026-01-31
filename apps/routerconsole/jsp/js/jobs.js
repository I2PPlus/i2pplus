/* I2P+ jobs.js by dr|z3d */
/* Handle refresh, table sorts on /jobs pages */
/* License: AGPL3 or later */

(function() {
  const jobs = document.getElementById("jobstats");
  if (!jobs) {return;}

  const REFRESH_INTERVAL = 5000;
  const sorter = new Tablesort(jobs, { descending: true });
  const progressx = window.progressx;
  const theme = window.theme;

  let refreshIntervalId = null;
  let oldRowsMap = new Map();

  async function fetchJobs() {
    try {
      const response = await fetch("/jobs" + window.location.search);
      if (!response.ok) throw new Error("Fetch failed");
      const text = await response.text();
      const doc = new DOMParser().parseFromString(text, "text/html");
      const jobsResponse = doc.getElementById("jobstats");
      if (!jobsResponse) return;

      const oldTbody = jobs.querySelector("#statCount");
      const newTbody = jobsResponse.querySelector("#statCount");
      if (!oldTbody || !newTbody) return;

      if (oldRowsMap.size === 0) {
        Array.from(oldTbody.rows).forEach(row => {
          const name = row.cells[0]?.textContent.trim();
          if (name) oldRowsMap.set(name, row);
        });
      }

      progressx.show(theme);
      requestAnimationFrame(() => {
        const newRows = Array.from(newTbody.rows);
        const fragment = document.createDocumentFragment();
        const updatedRows = [];

        newRows.forEach(newRow => {
          const jobName = newRow.cells[0]?.textContent.trim();
          if (!jobName) return;

          const oldRow = oldRowsMap.get(jobName);
          if (!oldRow) {
            fragment.appendChild(newRow);
            oldRowsMap.set(jobName, newRow);
            return;
          }

          const oldCells = Array.from(oldRow.cells);
          const newCells = Array.from(newRow.cells);
          let changed = false;

          newCells.forEach((newCell, j) => {
            const oldCell = oldCells[j];
            if (!oldCell) return;

            const oldSpan = oldCell.querySelector("span");
            const newSpan = newCell.querySelector("span");
            const oldText = (oldSpan ? oldSpan.textContent : oldCell.textContent).trim();
            const newText = (newSpan ? newSpan.textContent : newCell.textContent).trim();

            if (oldText !== newText) {
              oldCell.innerHTML = newCell.innerHTML;
              oldCell.classList.add("updated");
              changed = true;
            }
          });

          if (changed) {
            oldRowsMap.set(jobName, oldRow);
            updatedRows.push(oldRow);
            setTimeout(() => {
              oldCells.forEach(cell => cell.classList.remove("updated"));
            }, REFRESH_INTERVAL - 500);
          }
          const footer = jobs.querySelector(".tablefooter");
          const footerResponse = doc.querySelector(".tablefooter");
          footer.innerHTML = footerResponse.innerHTML;
        });

        if (fragment.hasChildNodes()) {
          oldTbody.appendChild(fragment);
        }

        updatedRows.forEach(row => {
          const jobName = row.cells[0]?.textContent.trim();
          if (jobName) oldRowsMap.set(jobName, row);
        });

        sorter.refresh();
        setTimeout(() => progressx.hide(), 500);
      });
    } catch (e) {
      progressx.hide();
    }
  }

  function startRefresh() {
    if (!refreshIntervalId) {
      fetchJobs();
      refreshIntervalId = setInterval(fetchJobs, REFRESH_INTERVAL);
    }
  }

  function stopRefresh() {
    if (refreshIntervalId) {
      clearInterval(refreshIntervalId);
      refreshIntervalId = null;
    }
  }

  jobs.addEventListener("beforeSort", () => {
    progressx.show(theme);
    progressx.progress(0.5);
  });

  jobs.addEventListener("afterSort", () => {
    setTimeout(() => progressx.hide(), 500);
  });

  if (document.visibilityState === "visible") startRefresh();

  document.addEventListener("visibilitychange", () => {
    document.visibilityState === "visible" ? startRefresh() : stopRefresh();
  });

  requestAnimationFrame(() => {
    sorter.refresh();
  });
})();