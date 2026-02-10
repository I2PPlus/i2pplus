/* I2P+ profiles.js by dr|z3d */
/* Handle refresh, table sorts, and tier counts on /profiles pages */
/* License: AGPL3 or later */

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
  const sessionBans = document.getElementById("sessionBanned");
  const uri = window.location.search.substring(1) !== "" ? window.location.pathname + "?" + window.location.search.substring(1) : window.location.pathname;
  let sorterFF = null;
  let sorterP = null;
  let sorterBans = null;

  function initRefresh() {
    addSortListeners();
    setupRefreshes();
  }

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

  function setupRefreshes() {
    // Refresh profiles overview and thresholds every 5 seconds
    if (info || thresholds) {
      const targetSelectors = [info, thresholds].filter(el => el).map(el => `#${el.id}`).join(", ");
      refreshElements(targetSelectors, uri, 5000);
    }

    // Refresh profile list every 15 seconds
    if (plist) {
      const targetSelectors = pbody ? `#pbody` : `#profilelist`;
      refreshElements(targetSelectors, uri, 15000);
    }

    // Refresh floodfill profiles every 15 seconds
    if (ff) {
      const targetSelectors = ffprofiles ? `#ffProfiles` : `#floodfills`;
      refreshElements(targetSelectors, uri, 15000);
    }

    // Refresh session bans every 15 seconds
    if (sessionBans) {
      const targetSelectors = "#sessionBanlist tr, #banSummary";
      refreshElements(targetSelectors, uri, 15000);
    }

    document.addEventListener("refreshComplete", () => {
      addSortListeners();
      if (sorterP) {sorterP.refresh();}
      if (sorterFF) {sorterFF.refresh();}
      if (sorterBans) {sorterBans.refresh();}
      if (banBody) {updateBanSummary(banBody);}
    });

  }

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
                       .replace(/^-\s*/, "")  // Remove leading dash
                       .replace(/^ \-> /, "") // Remove leading " -> "
                       .replace(/Blocklist:\s*[\d.:a-f]+/i, "Blocklist");
        reason = reason.trim();
        if (reason) { reasonCounts[reason] = (reasonCounts[reason] || 0) + 1; }
      }
    });

    let sorted = Object.entries(reasonCounts).sort((a, b) => {
      if (b[1] !== a[1]) { return b[1] - a[1]; }
      return a[0].localeCompare(b[0]);
    });

    let summaryDiv = document.getElementById("banSummary");
    let html = `<h2>Total Session Bans: ${total}</h2>\n<ul>`;
    sorted.forEach(([reason, count]) => { html += `<li><span class=badge>${count}</span> ${reason}</li>\n`; });
    html += "</ul>";

    if (!summaryDiv) {
      summaryDiv = document.createElement("div");
      summaryDiv.id = "banSummary";
      summaryDiv.style.marginBottom = "15px";
      summaryDiv.style.padding = "10px";
      if (sessionBans && sessionBans.parentNode) {
        sessionBans.parentNode.insertBefore(summaryDiv, sessionBans);
      }
    }
    summaryDiv.innerHTML = html;
    const footer = document.getElementById("sessionBanlistFooter");
    if (footer) {footer.remove();}
  }

  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    if (banBody) {updateBanSummary(banBody);}
 });
})();