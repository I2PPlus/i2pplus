/* I2PTunnel Throttler Toggle by dr|z3d 2024 */

var toggleThrottler = document.getElementById("toggleThrottler");
var throttleHeader = document.getElementById("throttleHeader");
var tunnelThrottler = document.getElementById("tunnelThrottler");

if (toggleThrottler.hasAttribute("hidden")) {
  toggleThrottler.removeAttribute("hidden");
}

if (throttleHeader.classList.length === 0) {
  tunnelThrottler.style.display = "none";
}

function toggleElement() {
  if (tunnelThrottler.style.display === "none") {
    tunnelThrottler.style.display = "table-row";
    if (!throttleHeader.classList.contains("isDisplayed")) {
      throttleHeader.classList.add("isDisplayed");
    }
  } else {
    tunnelThrottler.style.display = "none";
    if (throttleHeader.classList.contains("isDisplayed")) {
      throttleHeader.classList.remove("isDisplayed");
    }
  }
  localStorage.setItem("toggleState", tunnelThrottler.style.display);
}

if (localStorage.getItem("toggleState")) {
  var toggleState = localStorage.getItem("toggleState");
  tunnelThrottler.style.display = toggleState;
}

throttleHeader.addEventListener("click", toggleElement);