/**
 * @module profiles
 * @description Handles auto-refresh, table sorting, and session ban summaries
 * for the /profiles pages, including profile lists, floodfill profiles, and banlist.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from "./refreshElements.js";

(function() {
  const banlist = document.getElementById("banlist");
  const banBody = document.getElementById("sessionBanlist");
  const bfoot = document.getElementById("sessionBanlistFooter");
  const ff = document.getElementById("floodfills");
  const ffprofiles = document.getElementById("ffProfiles");
  const info = document.getElementById("profiles_overview");
  const main = document.getElementById("profiles");
  const pbody = document.getElementById("pbody");
  const plist = document.getElementById("profilelist");
  const thresholds = document.getElementById("thresholds");
  const sessionBans = document.getElementById("sbans");
  const uri = window.location.search.substring(1) !== "" ? window.location.pathname + "?" + window.location.search.substring(1) : window.location.pathname;
  let sorterFF = null;
  let sorterP = null;
  let sorterBans = null;
  const disabledReasons = new Set();

  /**
   * Initializes sort listeners and refresh schedules for profile pages.
   * @function initRefresh
   * @returns {void}
   */
  function initRefresh() {
    addSortListeners();
    setupRefreshes();
  }

  /**
   * Sets up Tablesort instances and progress bar handlers for profile tables.
   * @function addSortListeners
   * @returns {void}
   */
  function addSortListeners() {
    if (ff && sorterFF === null) {
      sorterFF = new Tablesort(ff, {descending: true});
      ff.addEventListener("beforeSort", () => progressx.show(theme));
      ff.addEventListener("afterSort", () => progressx.hide());
    } else if (plist && sorterP === null) {
      sorterP = new Tablesort(plist, {descending: true});
      plist.addEventListener("beforeSort", () => progressx.show(theme));
      plist.addEventListener("afterSort", () => progressx.hide());
    } else if (sessionBans && sorterBans === null) {
      sorterBans = new Tablesort(sessionBans, {descending: false});
      sessionBans.addEventListener("beforeSort", () => progressx.show(theme));
      sessionBans.addEventListener("afterSort", () => progressx.hide());
    }
  }

  /**
   * Configures periodic element refresh for profiles overview, lists, floodfills, and bans.
   * @function setupRefreshes
   * @returns {void}
   */
  function setupRefreshes() {
    // Refresh profiles overview and thresholds every 5 seconds
    if (info || thresholds) {
      const targetSelectors = [info, thresholds].filter(el => el).map(el => `#${el.id}`).join(", ");
      refreshElements(targetSelectors, uri, 5000);
    }

    // Refresh profile list every 15 seconds
    if (plist) {
      const targetSelectors = pbody ? `#pbody, #profiles_overview, #thresholds` : `#profilelist`;
      refreshElements(targetSelectors, uri, 15000);
    }

    // Refresh floodfill profiles every 15 seconds
    if (ff) {
      const targetSelectors = ffprofiles ? `#ffProfiles` : `#floodfills`;
      refreshElements(targetSelectors, uri, 15000);
    }

    // Refresh session bans every 15 seconds
    if (sessionBans) {
      const targetSelectors = "#sessionBanlist, #banSummary h2";
      refreshElements(targetSelectors, uri, 15000);
    }

    document.addEventListener("refreshComplete", () => {
      addSortListeners();
      if (sorterP) {sorterP.refresh();}
      if (sorterFF) {sorterFF.refresh();}
      if (sorterBans) {sorterBans.refresh();}
    });

  }

  /**
   * Filters the session ban table based on active reasons in the summary list.
   * @function filterBanTable
   * @returns {void}
   */
  function filterBanTable() {
    if (!banBody) return;
    const activeReasons = new Set();
    document.querySelectorAll("#banSummary ul.ban-reasons li.active").forEach(li => {
      let reason = li.textContent.replace(/^\d+\s*/, "").trim();
      activeReasons.add(reason);
    });
    banBody.querySelectorAll("tr").forEach(row => {
      const reasonCell = row.querySelector("td.reason");
      if (!reasonCell) { return; }
      let reason = reasonCell.textContent;
      reason = reason.split("(")[0].trim();
      reason = reason.replace("<b> -&gt; </b>", "")
                     .replace("<b> -> </b>", "")
                     .replace(/>\s*/, "")
                     .replace(/->\s*/, "")
                     .replace(/➜\s*/, "")
                     .replace(/^-\s*/, "")
                     .replace(/^ \-> /, "")
                     .replace("Excessive NTCP connection", "Excessive connection")
                     .replace(/Blocklist:\s*[\d.:a-f]+/i, "Blocklist");
      reason = reason.trim();
      row.style.display = activeReasons.has(reason) ? "" : "none";
    });
  }

  /**
   * Generates and displays a summary of session bans grouped by reason.
   * @function updateBanSummary
   * @param {HTMLElement} banBody - The table body containing ban rows
   * @returns {void}
   */
  function updateBanSummary(banBody) {
    const rows = banBody.querySelectorAll("tr");
    const reasonCounts = {};
    const total = rows.length;

    rows.forEach((row) => {
      const reasonCell = row.querySelector("td.reason");
      if (reasonCell) {
        let reason = reasonCell.textContent;
        reason = reason.split("(")[0].trim();
        reason = reason.replace("<b> -&gt; </b>", "")
                       .replace("<b> -> </b>", "")
                       .replace(/>\s*/, "")
                       .replace(/->\s*/, "")
                       .replace(/➜\s*/, "")
                       .replace(/^-\s*/, "")  // Remove leading dash
                       .replace(/^ \-> /, "") // Remove leading " -> "
                       .replace("Excessive NTCP connection", "Excessive connection")
                       .replace(/Blocklist:\s*[\d.:a-f]+/i, "Blocklist");
        reason = reason.trim();
        if (reason) { reasonCounts[reason] = (reasonCounts[reason] || 0) + 1; }
      }
    });

    let sorted = Object.entries(reasonCounts).sort((a, b) => {
      if (b[1] !== a[1]) { return b[1] - a[1]; }
      return a[0].localeCompare(b[0]);
    });

    // Auto-disable categories with >= 1000 entries for performance
    sorted.forEach(([reason, count]) => {
      if (count >= 1000) { disabledReasons.add(reason); }
    });

    let summaryDiv = document.getElementById("banSummary");
    let html = `<h2>Total Session Bans: ${total}</h2>\n<ul class="ban-reasons">`;
    sorted.forEach(([reason, count]) => {
      const cls = disabledReasons.has(reason) ? "" : " active";
      html += `<li class="${cls}"><span class=badge>${count}</span> ${reason}</li>\n`;
    });
    html += "</ul>";

    if (!summaryDiv) {
      summaryDiv = document.createElement("div");
      summaryDiv.id = "banSummary";
      if (sessionBans && sessionBans.parentNode) {
        sessionBans.parentNode.insertBefore(summaryDiv, sessionBans);
      }
    }
    summaryDiv.innerHTML = html;
    filterBanTable();
    const footer = document.getElementById("sessionBanlistFooter");
    if (footer) {footer.remove();}
  }

  document.addEventListener("click", (e) => {
    const li = e.target.closest("#banSummary ul.ban-reasons li");
    if (!li) return;
    li.classList.toggle("active");
    const reason = li.textContent.replace(/^\d+\s*/, "").trim();
    if (li.classList.contains("active")) {
      disabledReasons.delete(reason);
    } else {
      disabledReasons.add(reason);
    }
    filterBanTable();
  });

  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    if (banBody) {
      updateBanSummary(banBody);
      new MutationObserver(() => updateBanSummary(banBody))
        .observe(banBody, {childList: true});
    }
 });
})();