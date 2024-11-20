/* I2P+ click.js by dr|z3d for I2PSnark */
/* Simulate longer button clicks by adding .depress class to inputs */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", () => {
  const inputs = ["input[type=submit]", "input[type=reset]", ".filter", ".snarkNav", ".toggleview", "#tab_config"];
  document.documentElement.addEventListener("click", (event) => {
    const target = event.target;
    if (inputs.some(selector => target.matches(selector))) {
      target.classList.add("depress");
      setTimeout(() => {
        target.classList.replace("depress", "inert");
        setTimeout(() => target.classList.remove("inert"), 200);
      }, 360);
    }
  });
});