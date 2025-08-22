/* I2P+ profiles.js by dr|z3d */
/* Handle refresh, table sorts, and tier counts on /profiles pages */
/* License: AGPL3 or later */

(function() {
  const banlist = document.getElementById("banlist");
  const bbody = document.getElementById("sessionBanlist");
  const bfoot = document.getElementById("sessionBanlistFooter");
  const ff = document.getElementById("floodfills");
  const ffprofiles = document.getElementById("ffProfiles");
  const info = document.getElementById("profiles_overview");
  const main = document.getElementById("profiles");
  const pbody = document.getElementById("pbody");
  const plist = document.getElementById("profilelist");
  const thresholds = document.getElementById("thresholds");
  const refreshProfilesId = setInterval(refreshProfiles, 60000);
  const sessionBans = document.getElementById("sessionBanned");
  const uri = window.location.search.substring(1) !== "" ? window.location.pathname + "?" + window.location.search.substring(1) : window.location.pathname;
  let sorterFF = null;
  let sorterP = null;
  let sorterBans = null;
  const xhrprofiles = new XMLHttpRequest();

  function initRefresh() {addSortListeners();}

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

  function refreshProfiles() {
    if (uri.includes("?") && !uri.includes("f=3")) {xhrprofiles.open("GET", uri + "&t=" + Date.now(), true);}
    else if (!uri.includes("f=3")) {xhrprofiles.open("GET", uri, true);}
    xhrprofiles.responseType = "document";
    xhrprofiles.onreadystatechange = function() {
      if (xhrprofiles.readyState === 4 && xhrprofiles.status === 200 && !uri.includes("f=3")) {
        progressx.show(theme);
        if (info) {
          const infoResponse = xhrprofiles.responseXML.getElementById("profiles_overview");
          info.innerHTML = infoResponse.innerHTML;
        }
        if (plist) {
          addSortListeners();
          if (pbody) {
            const pbodyResponse = xhrprofiles.responseXML.getElementById("pbody");
            pbody.innerHTML = pbodyResponse.innerHTML;
            sorterP.refresh();
          } else {
            const plistResponse = xhrprofiles.responseXML.getElementById("profilelist");
            plist.innerHTML = plistResponse.innerHTML;
          }
        }
        if (thresholds) {
          const thresholdsResponse = xhrprofiles.responseXML.getElementById("thresholds");
          thresholds.innerHTML = thresholdsResponse.innerHTML;
        }
        if (ff) {
          addSortListeners();
          if (ffprofiles) {
            const ffprofilesResponse = xhrprofiles.responseXML.getElementById("ffProfiles");
            ffprofiles.innerHTML = ffprofilesResponse.innerHTML;
            sorterFF.refresh();
          } else {
            const ffResponse = xhrprofiles.responseXML.getElementById("floodfills");
            ff.innerHTML = ffResponse.innerHTML;
          }
        }
        if (sessionBans) {
          addSortListeners();
          if (bbody) {
            progressx.show(theme);
            const bbodyResponse = xhrprofiles.responseXML.getElementById("sessionBanlist");
            const bfootResponse = xhrprofiles.responseXML.getElementById("sessionBanlistFooter");
            bbody.innerHTML = bbodyResponse.innerHTML;
            bfoot.innerHTML = bfootResponse.innerHTML;
            updateBanSummary(banBody);
            sorterBans.refresh();
          }
        }
        progressx.hide();
      }
      if (ff || plist || sessionBans) {addSortListeners();}
      if (!uri.includes("f=3")) {xhrprofiles.send();}
    }
  }

  function updateBanSummary(banBody) {
    if (theme !== "dark") {return;}
    const rows = banBody.querySelectorAll("tr");
    const reasonCounts = {};
    const total = rows.length;

    rows.forEach((row) => {
      const reasonCell = row.querySelector("td:first-child");
      if (reasonCell) {
        let reason = reasonCell.textContent;
        reason = reason.split("(")[0].trim();
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
  }

  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    progressx.hide();
    const bbody = document.getElementById("sessionBanlist");
    if (bbody) { updateBanSummary(bbody); }
 });
})();