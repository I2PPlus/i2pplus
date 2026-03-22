/**
 * @module jobs
 * @description Handles auto-refresh, table sorting, and section toggle state
 * persistence for the /jobs pages. Manages expand/collapse state of finished
 * and scheduled job sections via localStorage.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  const jobs = document.getElementById("jobstats");
  const STORAGE_KEY = "jobqueueExpandedSections";
  let isFirstLoad = true;

  /**
   * Loads expanded section IDs from localStorage.
   * @function loadExpandedSections
   * @returns {Set<string>} Set of expanded section IDs
   */
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

  /**
   * Saves the expanded section IDs to localStorage.
   * @function saveExpandedSections
   * @param {Set<string>} expandedSections - Set of section IDs to save
   * @returns {void}
   */
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

  /**
   * Initializes section toggles for the jobqueue page, applying default and
   * stored expand/collapse states.
   * @function initJobqueueToggles
   * @returns {boolean} True if toggles were initialized, false if not applicable
   */
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
        if (toggle.classList.contains("nojobs")) {
          toggle.style.pointerEvents = "none";
        } else {
          toggle.style.pointerEvents = "";
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
  const header = document.getElementById("totaljobstats");
  const headerText = header ? header.innerHTML.trim() : "";

  let refreshIntervalId = null;
  let oldRowsMap = new Map();
  let lastQueryParams = window.location.search;

  /**
   * Fetches job data from /jobs, compares with existing rows, and updates the DOM
   * with new, modified, or removed rows.
   * @async
   * @function fetchJobs
   * @returns {Promise<void>}
   */
  async function fetchJobs() {
    try {
      // Clear old rows if query param changed (switching between 30s/all mode)
      if (window.location.search !== lastQueryParams) {
        oldRowsMap.clear();
        lastQueryParams = window.location.search;
      }

      const response = await fetch("/jobs" + window.location.search);
      if (!response.ok) throw new Error("Fetch failed");
      const text = await response.text();
      const doc = new DOMParser().parseFromString(text, "text/html");
      const jobsResponse = doc.getElementById("jobstats");
      if (!jobsResponse) return;

      const oldTbody = jobs.querySelector("#statCount");
      const newTbody = jobsResponse.querySelector("#statCount");
      if (!oldTbody || !newTbody) return;

      // If no rows in recent mode, show placeholder and force refresh
      const isRecentMode = !window.location.search.includes("period=all");
      const hasPlaceholder = oldTbody.querySelector("#init");
      const isAdvanced = jobs.classList.contains("advmode");
      const cols = isAdvanced ? "11" : "7";
      if (isRecentMode && newTbody.rows.length === 0 && !hasPlaceholder) {
        oldTbody.innerHTML = "<tr id=init><td colspan=" + cols + ">...</td></tr>";
        requestAnimationFrame(fetchJobs); // Force refresh
        return;
      }

      const thead = jobs.querySelector("thead");
      const tfoot = jobs.querySelector("#statTotals");
      const newTfoot = jobsResponse.querySelector("#statTotals");
      if (newTbody.rows.length === 0) {
        thead.style.display = "none";
        tfoot.style.display = "none";
      } else {
        thead.style.display = "";
        tfoot.style.display = "";
      }

      // Update statTotals footer with new data
      if (tfoot && newTfoot) {
        const currentContent = tfoot.innerHTML.trim();
        const newContent = newTfoot.innerHTML.trim();
        if (currentContent !== newContent) {
          tfoot.innerHTML = newContent;
        }
      }

      // Always repopulate oldRowsMap to stay in sync with current DOM
      oldRowsMap.clear();
      Array.from(oldTbody.rows).forEach(row => {
        const name = row.cells[0]?.textContent.trim();
        if (name) oldRowsMap.set(name, row);
      });

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
            fragment.appendChild(newRow.cloneNode(true));
            oldRowsMap.set(jobName, newRow.cloneNode(true));
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
        });

        // Remove rows only in recent mode that are no longer in the data
        const isRecentMode = !window.location.search.includes("period=all");
        if (isRecentMode) {
          const newRowNames = new Set(newRows.map(r => r.cells[0]?.textContent.trim()).filter(n => n));
          oldRowsMap.forEach((row, name) => {
            if (!newRowNames.has(name)) {
              row.remove();
              oldRowsMap.delete(name);
            }
          });
        }

        // Add new rows that didn't exist before
        if (fragment.hasChildNodes()) {
          oldTbody.appendChild(fragment);
        }

        const footer = jobs.querySelector(".tablefooter");

        sorter.refresh();
        setTimeout(() => progressx.hide(), 500);
      });
    } catch (e) {
      progressx.hide();
    }
  }

  /**
   * Starts the periodic job data refresh interval.
   * @function startRefresh
   * @returns {void}
   */
  function startRefresh() {
    if (!refreshIntervalId) {
      fetchJobs();
      refreshIntervalId = setInterval(fetchJobs, REFRESH_INTERVAL);
    }
  }

  /**
   * Stops the periodic job data refresh interval.
   * @function stopRefresh
   * @returns {void}
   */
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

  if (document.visibilityState === "visible") { startRefresh(); }

  document.addEventListener("visibilitychange", () => {
    document.visibilityState === "visible" ? startRefresh() : stopRefresh();
  });

  requestAnimationFrame(() => {
    sorter.refresh();
  });
})();
