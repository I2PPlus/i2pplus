/* I2P+ filterBar.js by dr|z3d */
/* Setup I2PSnark torrent display buttons so we can show/hide snarks
/* based on status and load filtered content via AJAX calls */
/* License: AGPL3 or later */

import {refreshTorrents, xhrsnark, doRefresh} from "./refreshTorrents.js";
import {onVisible} from "./onVisible.js";

let filterbar;
let snarkCount;

function showBadge() {
  filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {return;}
  const query = new URLSearchParams(window.location.search), filterQuery = query.get("filter"), searchQuery = query.get("search");
  const allFilters = filterbar.querySelectorAll(".filter");
  const filterId = searchQuery !== null ? "search" : filterQuery || "all";
  const activeFilter = document.getElementById(filterId);
  const filterAll = document.getElementById("all");

  allFilters.forEach(filter => {
    const badges = filter.querySelectorAll(".filter:not(#all) .badge");
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
      badges.forEach(badge => {
        badge.hidden = true;
        badge.textContent = "";
      });
    } else {badges.forEach(badge => badge.hidden = false);}
  });

  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      const activeBadge = activeFilter.querySelector(".badge");
      activeBadge.id = "filtercount";
      snarkCount = countSnarks();
      if (filterId !== "all") {document.getElementById("filtercount").textContent = snarkCount}
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }

    const allBadge = filterAll.querySelector("#filtercount.badge");
    if (filterAll.classList.contains("enabled")) {allBadge?.removeAttribute("hidden");}
    else {allBadge?.setAttribute("hidden", "");}
  }
}

function countSnarks() { return torrents.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length; }

function updateURLs() {
  var xhrURL = "/i2psnark/.ajax/xhr1.html" + window.location.search;
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sorter");

  sortIcon.forEach((item) => {
    item.addEventListener("click", () => { setQuery(); });
  });

  function setQuery() {
    const params = window.location.search;
    if (params) {const storage = window.localStorage.setItem("queryString", params);}
  }
}

function checkIfVisible() {
  const torrentform = document.getElementById("torrentlist");
  if (torrentform !== null) { onVisible(torrentform, () => {updateURLs();}); }
}

function filterNav() {
  const filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {setTimeout(filterNav, 1000); return;}
  const torrents = document.getElementById("torrents"), torrentForm = document.getElementById("torrentlist"), pagenavtop = document.getElementById("pagenavtop");
  filterbar.addEventListener("click", function(event) {
    const filterElement = event.target.closest(".filter");
    if (filterElement) {
      event.preventDefault();
      if (!filterElement.classList.contains("enabled")) {filterElement.classList.add("enabled");}
      const filterURL = new URL(filterElement.href), xhrURL = "/i2psnark/.ajax/xhr1.html" + filterURL.search; history.replaceState({}, "", filterURL);
      doRefresh(xhrURL, updateURLs);
      if (pagenavtop) { pagenavtop.hidden = filterElement.id !== "all"; }
      showBadge();
    }
  });
}

document.addEventListener("DOMContentLoaded", function() {
  checkIfVisible();
  filterNav();
  showBadge();
});

export {updateURLs, showBadge};