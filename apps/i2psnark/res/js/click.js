/* I2P+ click.js for I2PSnark by dr|z3d */
/* Simulate longer button clicks by adding .depress class to inputs */
/* License: AGPL3 or later */

/* I2P+ click.js for I2PSnark by dr|z3d */
/* Simulate longer button clicks by adding .depress class to inputs */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", () => {
  const inputs = ["input[type=submit]", "input[type=reset]", "input[class^='action']", ".control", ".filter", ".snarkNav", ".toggleview", "#tab_config"];
  const iframe = document.getElementById("processForm");

  const handleInputClick = (clickTarget) => {
    const currentForm = clickTarget.closest("form");
    const nonClickedSubmitElements = currentForm && currentForm.id === "torrentslist" ? currentForm.querySelectorAll("input[type=submit][class^='action']:not(.depress)") : [];
    clickTarget.classList.add("depress");

  const timeout = () => {
    setTimeout(() => {
      clickTarget.classList.replace("depress", "inert");
      nonClickedSubmitElements.forEach((el) => el.classList.remove("tempDisabled"));
    }, 500);
  };

    let delay = 600;
    const isAction = clickTarget.matches("input[class^='action']");
    if (iframe && isAction) {
      const formTarget = clickTarget.form.target;
      if (formTarget === "processForm" && isAction) {
        delay = 4000;
        iframe.addEventListener("load", () => setTimeout(timeout, delay));
      }
    } else {
      if (clickTarget.classList.contains("add") || clickTarget.classList.contains("create")) {
        event.preventDefault();
      }
      nonClickedSubmitElements.forEach((el) => el.classList.add("tempDisabled"));
      setTimeout(timeout, delay);
    }
  }

  document.body.addEventListener("click", (event) => {
    const clickTarget = event.target;
    if (inputs.some((selector) => clickTarget.matches(selector))) {
      if (clickTarget.matches("input[class='actionRemove']") || clickTarget.matches("input[class='actionDelete']")) {return;}
      handleInputClick(clickTarget);
    }
  });
});