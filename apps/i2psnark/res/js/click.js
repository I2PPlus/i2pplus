/* I2P+ click.js for I2PSnark by dr|z3d */
/* Simulate longer button clicks by adding .depress class to inputs */
/* License: AGPL3 or later */

import {refreshScreenLog} from "./refreshTorrents.js";

document.addEventListener("DOMContentLoaded", () => {
  const inputs = [
    "input[type=submit]",
    "input[type=reset]",
    "input[class^='action']",
    ".control",
    ".filter",
    ".snarkNav",
    ".toggleview",
    "#tab_config",
    ".pagenavcontrols a span"
  ];
  const iframe = document.getElementById("processForm");
  const page = document.getElementById("page");

  const handleInputClick = async (clickTarget) => {
    let delay = 400;
    const isAction = clickTarget.matches("input[class^='action']");
    const isAddOrCreate = clickTarget.matches("input.add") || clickTarget.matches("input.create");
    const currentForm = clickTarget.closest("form");
    const nonClickedSubmitElements = currentForm && currentForm.id === "torrentslist"
      ? currentForm.querySelectorAll("input[type=submit][class^='action']:not(.depress)")
      : [];

    clickTarget.classList.add("depress");

    if (isAddOrCreate) {
      event.preventDefault();
      await refreshScreenLog(undefined, true);
    }
    else if (iframe && isAction) {
      const formTarget = clickTarget.form.target;
      if (formTarget === "processForm" && isAction) {delay = 4000;}
    } else {
      nonClickedSubmitElements.forEach((el) => el.classList.add("tempDisabled"));
    }
    setTimeout(() => {
      clickTarget.classList.replace("depress", "inert");
      nonClickedSubmitElements.forEach((el) => el.classList.remove("tempDisabled"));
    }, delay);
  };

  page.addEventListener("click", (event) => {
    const clickTarget = event.target;
    if (inputs.some((selector) => clickTarget.matches(selector))) {
      if (clickTarget.matches("input[class='actionRemove']") || clickTarget.matches("input[class='actionDelete']")) {return;}
      handleInputClick(clickTarget);
    }
  });
});