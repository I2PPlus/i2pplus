/* I2P+ toggleThrottler.js for I2PTunnel Manager by dr|z3d */
/* Toggle server throttler section and remember toggle status */
/* License: AGPL3 or later */

(() => {
  const toggleThrottler = document.getElementById("toggleThrottler");
  const throttleHeader = document.getElementById("throttleHeader");
  const tunnelThrottler = document.getElementById("tunnelThrottler");

  if (toggleThrottler.hidden) toggleThrottler.hidden = false;
  if (!throttleHeader.classList.length) tunnelThrottler.style.display = "none";

  const toggleElement = () => {
    const isDisplayed = tunnelThrottler.style.display === "none";
    tunnelThrottler.style.display = isDisplayed ? "table-row" : "none";
    throttleHeader.classList.toggle("isDisplayed", isDisplayed);
    localStorage.setItem("toggleState", tunnelThrottler.style.display);
  };

  const toggleState = localStorage.getItem("toggleState");
  if (toggleState) {
    tunnelThrottler.style.display = toggleState;
    throttleHeader.classList.toggle("isDisplayed", toggleState === "table-row");
  }

  throttleHeader.addEventListener("click", toggleElement);
})();