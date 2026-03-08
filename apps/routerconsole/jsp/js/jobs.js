/* I2P+ jobs.js by dr|z3d */
/* Handle refresh, table sorts on /jobs pages */
/* License: AGPL3 or later */

(function() {
  const jobs = document.getElementById("jobstats");
  const STORAGE_KEY = "jobqueueExpandedSections";
  let isFirstLoad = true;

  // Load expanded sections from localStorage or empty Set
  function loadExpandedSections() {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored) {
        return new Set(JSON.parse(stored));
      }
    } catch (e) {
      // localStorage not available or parse error
    }
    return new Set();
  }

  // Save expanded sections to localStorage
  function saveExpandedSections(expandedSections) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify([...expandedSections]));
    } catch (e) {
      // localStorage not available
    }
  }

  let expandedSections = loadExpandedSections();

  // Default states: true = expanded, false = collapsed
  const defaultStates = {
    "finishedjobs": false,  // Completed jobs collapsed by default
    "scheduledjobs": true   // Scheduled jobs expanded by default
  };

  // Setup section toggles for jobqueue page using event delegation
  function initJobqueueToggles() {
    const joblog = document.querySelector(".joblog");
    if (!joblog) return false;

    // Add toggle classes and collapse/expand based on stored state
    const sectionToggles = joblog.querySelectorAll("h3");
    sectionToggles.forEach(toggle => {
      toggle.classList.add("sectionToggle");
      toggle.classList.add("toggle");
      const dropdown = document.createElement("span");
      dropdown.classList.add("dropdown");
      toggle.appendChild(dropdown);
      const content = toggle.nextElementSibling;
      if (content && content.nodeType === Node.ELEMENT_NODE) {
        const sectionId = toggle.id;
        // On first load, apply default states if section not in localStorage
        if (isFirstLoad && !expandedSections.has(sectionId)) {
          // Check if user has ever set a preference (localStorage exists)
          const hasUserPreference = localStorage.getItem(STORAGE_KEY) !== null;
          if (!hasUserPreference && defaultStates.hasOwnProperty(sectionId)) {
            if (defaultStates[sectionId]) {
              expandedSections.add(sectionId);
            }
          }
        }
        if (expandedSections.has(sectionId)) {
          toggle.classList.add("expanded");
          content.style.display = "block";
        } else {
          toggle.classList.remove("expanded");
          content.style.display = "none";
        }
      }
    });

    isFirstLoad = false;

    if (sectionToggles.length > 0) {
      document.body.classList.add("toggleElementsActive");
      if (expandedSections.size > 0) {
        document.body.classList.add("hasExpandedElement");
        document.documentElement.classList.remove("hasCollapsedElement");
      } else {
        document.body.classList.remove("hasExpandedElement");
        document.documentElement.classList.add("hasCollapsedElement");
      }
    }
    return true;
  }

  // Event delegation for toggle clicks - attached to document level
  document.documentElement.addEventListener("click", function(e) {
    const toggle = e.target.closest(".joblog h3");
    if (!toggle) return;

    const content = toggle.nextElementSibling;
    if (!content || content.nodeType !== Node.ELEMENT_NODE) return;

    const sectionId = toggle.id;
    const isExpanded = toggle.classList.contains("expanded");

    // Toggle current section independently
    if (!isExpanded) {
      content.style.display = "block";
      toggle.classList.add("expanded");
      expandedSections.add(sectionId);
    } else {
      content.style.display = "none";
      toggle.classList.remove("expanded");
      expandedSections.delete(sectionId);
    }

    // Save to localStorage
    saveExpandedSections(expandedSections);

    // Update document/body classes based on any expanded sections
    if (expandedSections.size > 0) {
      document.documentElement.classList.remove("hasCollapsedElement");
      document.body.classList.add("hasExpandedElement");
    } else {
      document.documentElement.classList.add("hasCollapsedElement");
      document.body.classList.remove("hasExpandedElement");
    }
  });

  // Initialize on load
  if (initJobqueueToggles()) {
    // Listen for refreshComplete event to restore toggle state
    document.addEventListener("refreshComplete", function() {
      initJobqueueToggles();
    });
    return;
  }

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

        const newJobNames = new Set(newRows.map(row => row.cells[0]?.textContent.trim()).filter(Boolean));
        oldRowsMap.forEach((row, jobName) => {
          if (!newJobNames.has(jobName)) {
            row.remove();
            oldRowsMap.delete(jobName);
          }
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
