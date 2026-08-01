/**
 * @module newHosts
 * @description Fetches and displays new hostname registrations from /susidns/log.jsp
 * in the sidebar, with a badge counter and tooltip listing. Caches data in localStorage
 * and periodically refreshes. Only active on the dark theme.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** @type {number|null} */
let newHostsInterval = null;
/** @type {boolean} */
let tooltipInitialized = false;
/** @type {HTMLElement|null} */
let tooltipBadge = null;
/** @type {HTMLElement|null} */
let tooltipServices = null;

/**
 * Shows the new hosts tooltip; looks elements up at event time so the
 * handlers stay valid across sidebar full refreshes.
 * @function tooltipShow
 * @returns {void}
 */
function tooltipShow() {
  const newHostsList = document.getElementById("newHostsList");
  if (newHostsList) {newHostsList.hidden = false;}
  document.getElementById("sb_services")?.classList.add("tooltipped");
}

/**
 * Hides the new hosts tooltip; looks elements up at event time.
 * @function tooltipHide
 * @returns {void}
 */
function tooltipHide() {
  const newHostsList = document.getElementById("newHostsList");
  if (newHostsList) {newHostsList.hidden = true;}
  document.getElementById("sb_services")?.classList.remove("tooltipped");
}

/**
 * Attaches the tooltip listeners to the current badge node exactly once;
 * re-attaches if the badge was replaced by a sidebar full refresh.
 * @function ensureTooltipListeners
 * @param {HTMLElement} badge - The new hosts badge element
 * @returns {void}
 */
function ensureTooltipListeners(badge) {
  if (tooltipInitialized && tooltipBadge === badge) {return;}
  if (tooltipInitialized && tooltipBadge) {
    tooltipBadge.removeEventListener("mouseenter", tooltipShow);
    tooltipServices?.removeEventListener("mouseleave", tooltipHide);
  }
  badge.addEventListener("mouseenter", tooltipShow, { passive: true });
  tooltipServices = document.getElementById("sb_services");
  tooltipServices?.addEventListener("mouseleave", tooltipHide, { passive: true });
  tooltipBadge = badge;
  tooltipInitialized = true;
}

/**
 * Initializes the new hosts badge with periodic fetching, caching, and tooltip management.
 * @function newHosts
 * @returns {void}
 */
export function newHosts() {
  const newHostsBadge = document.getElementById("newHosts");
  if (!newHostsBadge) { return; }
  if (theme !== "dark") {
    newHostsBadge.style.display = "none";
    return;
  }

  const period = 30 * 60 * 1000;

  /**
   * Retrieves cached new hosts data from localStorage.
   * @function getStoredData
   * @returns {{hostnames: Array<{hostname: string, timestamp: number}>, count: number, lastUpdated: number|null}}
   */
  function getStoredData() {
    const key = "newHostsData";
    const rawData = localStorage.getItem(key);

    if (!rawData) {
      return { hostnames: [], count: 0, lastUpdated: null };
    }

    try {
      const parsed = JSON.parse(rawData);
      if (typeof parsed === "object" && !Array.isArray(parsed) && parsed !== null) {
        return parsed;
      }
      throw new Error("Data is not an object");
    } catch (e) {
      console.warn("Invalid JSON in localStorage, clearing:", key);
      localStorage.removeItem(key);
      return { hostnames: [], count: 0, lastUpdated: null };
    }
  }

  /**
   * Fetches the latest hosts from /susidns/log.jsp, filters by 24-hour window,
   * and updates localStorage and the badge display.
   * @function fetchNewHosts
   * @returns {void}
   */
  function fetchNewHosts() {
    localStorage.setItem("newHostsLastFetch", Date.now());

    fetch("/susidns/log.jsp")
      .then(response => response.text())
      .then(html => {
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, "text/html");

        const now = new Date();
        const oneDayAgo = new Date(now.getTime() - 24 * 60 * 60 * 1000);

        const entries = Array.from(doc.querySelectorAll("li"));
        const newHostnames = entries.flatMap(entry => {
          const dateText = entry.querySelector(".date")?.textContent;
          if (!dateText) return [];

          const entryDate = new Date(dateText);
          if (entryDate < oneDayAgo) return [];

          const links = entry.querySelectorAll("a");
          return Array.from(links).map(a => ({
            hostname: new URL(a.href).hostname,
            timestamp: entryDate.getTime()
          }));
        });

        const storedData = getStoredData();
        const storedHostnames = storedData.hostnames || [];

        const hostnameMap = new Map();
        [...storedHostnames, ...newHostnames].forEach(h => {
          if (!hostnameMap.has(h.hostname) || h.timestamp > hostnameMap.get(h.hostname)?.timestamp) {
            hostnameMap.set(h.hostname, h);
          }
        });

        const allHostnames = Array.from(hostnameMap.values())
          .filter(h => h.timestamp >= oneDayAgo.getTime())
          .sort((a, b) => b.timestamp - a.timestamp);

        const limitedHostnames = allHostnames.slice(0, 10);
        const sortedHostnames = limitedHostnames.map(h => h.hostname).sort();

        const count = sortedHostnames.length;
        localStorage.setItem("newHostsData", JSON.stringify({
          count,
          lastUpdated: Date.now(),
          hostnames: limitedHostnames
        }));

        if (count > 10) {
          newHostsBadge.textContent = "10+";
        } else {
          newHostsBadge.textContent = count || "";
        }

        updateTooltip(sortedHostnames);
      })
      .catch(err => console.error("Failed to fetch new hosts:", err));
  }

  /**
   * Retrieves new hosts from cache if recent enough, otherwise triggers a fresh fetch.
   * @function getNewHosts
   * @returns {void}
   */
  function getNewHosts() {
    const now = Date.now();
    const storedData = getStoredData();
    const lastFetch = parseInt(localStorage.getItem("newHostsLastFetch") || "0", 10);

    if (
      storedData.lastUpdated &&
      now - storedData.lastUpdated < 15*60*1000 &&
      now - lastFetch < 10*60*1000
    ) {
      const { count, hostnames } = storedData;
      if (count > 0) {
        if (count > 10) { newHostsBadge.textContent = "10+"; }
        else { newHostsBadge.textContent = count; }
        updateTooltip(hostnames.map(h => h.hostname));
      } else {
        newHostsBadge.textContent = "";
      }
    } else {
      fetchNewHosts();
    }
  }

  /**
   * Updates the tooltip content with a list of new hostnames as clickable links.
   * @function updateTooltip
   * @param {string[]} hostnames - Array of hostnames to display
   * @returns {void}
   */
  function updateTooltip(hostnames) {
    if (!newHostsBadge) { return; }

    const newHosts = document.getElementById("newHostsList");
    const newHostsTd = newHosts?.querySelector("td");

    if (!hostnames.length) {
      if (newHosts) { newHosts.hidden = true; }
      if (newHostsTd) { newHostsTd.innerHTML = ""; }
      return;
    }

    const newHostsList = hostnames.map(hostname => {
      const shortName = hostname.replace(".i2p", "");
      return `<a href="http://${hostname}" target="_blank">${shortName}</a>`;
    }).join("<br>");

    if (newHostsTd && newHostsTd.innerHTML !== newHostsList) { newHostsTd.innerHTML = newHostsList; }

    if (newHosts) { newHosts.hidden = true; }

    ensureTooltipListeners(newHostsBadge);
  }
  if (newHostsInterval) {clearInterval(newHostsInterval);}
  getNewHosts();
  newHostsInterval = setInterval(fetchNewHosts, period);
}