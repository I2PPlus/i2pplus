/* I2P+ filterBar.js by dr|z3d */
/* Setup I2PSnark torrent display buttons so we can show/hide snarks
/* based on status and load filtered content via AJAX calls */
/* License: AGPL3 or later */

import {refreshTorrents, doRefresh} from "./refreshTorrents.js";

let filterbar;
let snarkCount;
const torrents = document.getElementById("torrents");

async function showBadge() {
  filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {return;}
  const urlParams = new URLSearchParams(window.location.search), filterQuery = urlParams.get("filter"), searchQuery = urlParams.get("search");
  const filterId = searchQuery !== null ? "search" : (filterQuery || "all");
  const allFilters = filterbar.querySelectorAll(".filter"), activeFilter = document.getElementById(filterId), filterAll = document.getElementById("all");

  allFilters.forEach(filter => {
    const isFilterAll = filter.id === "all";
    const badges = filter.querySelectorAll(".badge");
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
      const tempDisabled = {pointerEvents: "none", opacity: ".5"};
      Object.assign(filter, tempDisabled);
      filter.style.pointerEvents = "none";
      filter.style.opacity = ".5";
      if (!isFilterAll) {
        badges.forEach(badge => { Object.assign(badge, {hidden: true, textContent: "", id: ""}); });
      } else {filter.querySelector(".badge").hidden = true;}
    } else if (!isFilterAll) {
      badges.forEach(badge => {
        setTimeout(() => {
          requestAnimationFrame(() => {
            Object.assign(badge, {textContent: countSnarks(), hidden: false, id: "filtercount"});
           const activeBadge = document.getElementById("filtercount");
           if (activeBadge?.textContent !== countSnarks()) {activeBadge.textContent = countSnarks();}
          });
        }, 1500);
      });
    } else {
      const badge = filterAll.querySelector(".badge");
      Object.assign(badge, {hidden: false, id: "filtercount"});
    }
    if (filter !== activeFilter) {
      setTimeout(() => {
       filter.style.pointerEvents = "";
       filter.style.opacity = "";
     }, 2000);
   }
  });

  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      const activeBadge = activeFilter.querySelector(".badge");
      activeBadge.id = "filtercount";
      await countSnarks();
      requestAnimationFrame(async () => {
        const snarkCount = await countSnarks();
        if (filterId !== "all") {activeBadge.textContent = snarkCount}
      });
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }
    const allBadge = filterAll.querySelector("#filtercount.badge");
    if (filterAll.classList.contains("enabled")) {allBadge?.removeAttribute("hidden");}
    else {allBadge?.setAttribute("hidden", "");}
  }
}

function countSnarks() {
  return torrents?.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length;
}

function updateURLs() {
  const torrentform = document.getElementById("torrentlist");
  if (!torrentform) {return;}
  var xhrURL = "/i2psnark/.ajax/xhr1.html" + window.location.search;
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sorter");

  sortIcon.forEach((item) => { item.addEventListener("click", () => { setQuery(); }); });

  function setQuery() {
    const params = window.location.search;
    if (params) {const storage = window.localStorage.setItem("queryString", params);}
  }
}

async function filterNav() {
  const filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) { setTimeout(filterNav, 1500); return; }
  const pagenavtop = document.getElementById("pagenavtop");
  filterbar.addEventListener("click", async function(event) {
    const filterElement = event.target.closest(".filter");
    if (filterElement) {
      event.preventDefault();
      if (!filterElement.classList.contains("enabled")) {filterElement.classList.add("enabled");}
      const filterURL = new URL(filterElement.href);
      const xhrURL = "/i2psnark/.ajax/xhr1.html" + filterURL.search;
      history.replaceState({}, "", filterURL);
      showBadge();
      try {await doRefresh(xhrURL, true);}
      catch {}
      if (pagenavtop) {pagenavtop.hidden = filterElement.id !== "all";}
    }
  });
}

document.addEventListener("DOMContentLoaded", function() { updateURLs(); filterNav(); countSnarks(); showBadge(); });

export {updateURLs, showBadge};