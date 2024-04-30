/* setFilterQuery.js by dr|z3d */
/* Assign filter parameter to main snark navigation and cancel search buttons */
/* License: AGPL3 or later */


const navbar = document.getElementById("navbar");
const filterLinks = document.querySelectorAll("#torrentDisplay .filter");

function setFilterQuery() {
  if (!navbar || !filterLinks) {return;}

  const navMain = document.querySelector(".nav_main");
  const cancelSearch = document.querySelector("#searchwrap a");
  navbar.addEventListener("click", (event) => {
    if (event.target.classList.contains("nav_main")) {
      const filter = localStorage.getItem("snarkFilter");
      if (filter && filter !== "all" && filter !== "") {
        navMain.href = "/i2psnark/?filter=" + filter;
      }
    }
  });

  if (!cancelSearch) {return;}
  cancelSearch.addEventListener("click", (event) => {
    if (event.target.href.includes("i2psnark")) {
      const filter = localStorage.getItem("snarkFilter");
      if (filter && filter !== "all" && filter !== "") {
        cancelSearch.href = "/i2psnark/?filter=" + filter;
      }
    }
  });

}

document.addEventListener("DOMContentLoaded", setFilterQuery);
