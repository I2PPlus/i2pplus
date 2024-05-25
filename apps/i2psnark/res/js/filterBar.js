/* filterBar.js by dr|z3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import { refreshTorrents } from "./refreshTorrents.js";
import { onVisible } from "./onVisible.js";

const filterbar = document.getElementById("torrentDisplay");
let debugging = true;

function initFilterBar() {
  if (!filterbar) {return;}

  const path = window.location.pathname;
  const query = window.location.search;
  const screenlog = document.getElementById("screenlog");
  const snarkFilter = "snarkFilter";
  const storage = window.localStorage.getItem(snarkFilter);
  const toggle = document.getElementById("linkswitch");

  /* buttons */
  const btnActive = document.querySelector("#torrentDisplay .filter#active");
  const btnAll = document.querySelector("#torrentDisplay .filter#all");
  const btnComplete = document.querySelector("#torrentDisplay .filter#complete");
  const btnConnected = document.querySelector("#torrentDisplay .filter#connected");
  const btnDownloading = document.querySelector("#torrentDisplay .filter#downloading");
  const btnInactive = document.querySelector("#torrentDisplay .filter#inactive");
  const btnIncomplete = document.querySelector("#torrentDisplay .filter#incomplete");
  const btnSeeding = document.querySelector("#torrentDisplay .filter#seeding");
  const btnStopped = document.querySelector("#torrentDisplay .filter#stopped");

  /* rows and table elements */
  const active = document.querySelectorAll("#snarkTbody .active");
  const allEven = document.querySelectorAll("#snarkTbody .rowEven");
  const allOdd = document.querySelectorAll("#snarkTbody .rowOdd");
  const complete = document.querySelectorAll("#snarkTbody .complete");
  const debuginfo = document.querySelectorAll("#snarkTbody .debuginfo");
  const downloading = document.querySelectorAll("#snarkTbody .downloading");
  const filtered = document.querySelectorAll(".filtered");
  const inactive = document.querySelectorAll("#snarkTbody .inactive:not(.peerinfo)");
  const incomplete = document.querySelectorAll("#snarkTbody .incomplete");
  const mainsection = document.getElementById("mainsection");
  const peerinfo = document.querySelectorAll("#snarkTbody .peerinfo");
  const seeding = document.querySelectorAll("#snarkTbody .seeding");
  const stopped = document.querySelectorAll("#snarkTbody .stopped");
  const tbody = document.getElementById("snarkTbody");
  const tfoot = document.getElementById("snarkFoot");
  const torrentform = document.getElementById("torrentlist");

  checkIfActive();

  function checkIfActive() {
    if (!storage && filterbar !== null) {
      btnAll.classList.add("enabled");
    }
  }

  function setFilter(id) {
    const filters = {
      active: { button: btnActive, key: "active" },
      all: { button: btnAll, key: "all" },
      complete: { button: btnComplete, key: "complete" },
      connected: { button: btnConnected, key: "connected" },
      downloading: { button: btnDownloading, key: "downloading" },
      inactive: { button: btnInactive, key: "inactive" },
      incomplete: { button: btnIncomplete, key: "incomplete" },
      seeding: { button: btnSeeding, key: "seeding" },
      stopped: { button: btnStopped, key: "stopped" },
    };

    //const filter = filters[id];
    //if (!filter) return;
    //window.localStorage.setItem("snarkFilter", filter.key);
  }

  if (filterbar) {
    const filterButtons = [
      { button: btnActive, filterFunction: () => {setFilter("active"), showBadge()}, localStorageKey: "active"},
      { button: btnAll, filterFunction: () => {setFilter("all"), showBadge()}, localStorageKey: ""},
      { button: btnComplete, filterFunction: () => {setFilter("complete"), showBadge()}, localStorageKey: "complete"},
      { button: btnConnected, filterFunction: () => {setFilter("connected"), showBadge()}, localStorageKey: "connected"},
      { button: btnDownloading, filterFunction: () => {setFilter("downloading"), showBadge()}, localStorageKey: "downloading"},
      { button: btnInactive, filterFunction: () => {setFilter("inactive"), showBadge()}, localStorageKey: "inactive"},
      { button: btnIncomplete, filterFunction: () => {setFilter("incomplete"), showBadge()}, localStorageKey: "incomplete"},
      { button: btnSeeding, filterFunction: () => {setFilter("seeding"), showBadge()}, localStorageKey: "seeding"},
      { button: btnStopped, filterFunction: () => {setFilter("stopped"), showBadge()}, localStorageKey: "stopped"},
    ];

    document.addEventListener("DOMContentLoaded", () => {
      mainsection.addEventListener("click", event => {
        const filterButton = event.target.closest(".filter");
        if (filterButton) {
          filterButton.classList.add("enabled");
          const filter = findFilterByButton(filterButton);
          filter.filterFunction();
          window.localStorage.setItem("snarkFilter", filterButton.id);

          function findFilterByButton(button) {
            return filterButtons.find(filter => filter.button === button);
          }

          if (storage === filter.localStorageKey) {
            const filterButton = filterButtons.find(filter => filter.localStorageKey === storage).button;
            filterButton.classList.add("enabled");
          }
        }
      });
    });
  }
}

function showBadge() {
  const query = new URLSearchParams(window.location.search);
  const filterQuery = query.get("filter");
  const allFilters = filterbar.querySelectorAll(".filter");
  const activeFilter = document.querySelector(".filter[id='" + (filterQuery != null ? filterQuery : "all") + "']");
  allFilters.forEach(filter => {
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
    }
  });
  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }
    const snarks = document.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length;
    const activeBadge = activeFilter.querySelector(".badge");
    const filterAll = torrentDisplay.querySelector("#all");
    activeBadge.id = "filtercount";
    if (!filterAll.classList.contains("enabled")) {
      activeBadge.textContent = snarks;
      filterAll.querySelector(".badge").setAttribute("hidden", "");
    } else {
      filterAll.querySelector(".badge").removeAttribute("hidden");
    }
  }
}

function checkFilterBar() {
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sortIcon");

  sortIcon.forEach((item) => {
    item.addEventListener("click", () => {
      setQuery();
    });
  });

  function setQuery() {
    if (window.location.search) {
      window.localStorage.setItem("queryString", window.location.search);
      window.location.search = window.location.search + (storage !== null && storage !== "") ? "&filter=" + storage : "";
    } else if (storage && storage !== "") {
      window.location.search += "?filter=" + storage;
    }
  }

}

function checkIfVisible() {
  const torrentform = document.getElementById("torrentlist");
  if (torrentform !== null) {
    onVisible(torrentform, () => {
      checkFilterBar();
    });
  }
}

document.addEventListener("DOMContentLoaded", checkIfVisible);

export {initFilterBar, checkFilterBar, showBadge};