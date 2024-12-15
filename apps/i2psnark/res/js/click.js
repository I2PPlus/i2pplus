/* I2P+ click.js for I2PSnark by dr|z3d */
/* Simulate longer button clicks by adding .depress class to inputs */
/* License: AGPL3 or later */

import {refreshScreenLog} from "./refreshTorrents.js";

document.addEventListener("DOMContentLoaded", () => {
  const page = document.getElementById("page");

  page.addEventListener("click", (event) => {
    const clickTarget = event.target;

    if ((clickTarget.matches("input.add") || clickTarget.matches("input.create"))) {
      event.preventDefault();
      event.stopPropagation();
      clickTarget.form.requestSubmit();
      console.log("create or add button clicked");
      handleInputClick(clickTarget);
    }
  });

  const handleInputClick = async (clickTarget) => {
    let delay = 400;
    const isAction = clickTarget.matches("input[class^='action']");
    const currentForm = clickTarget.closest("form");
    const nonClickedSubmitElements = currentForm && currentForm.id === "torrentslist"
      ? currentForm.querySelectorAll("input[type=submit][class^='action']:not(.depress)")
      : [];

    clickTarget.classList.add("depress");

    if (isAction) {
      const iframe = document.getElementById("processForm");
      if (iframe) {
        const formTarget = clickTarget.form.target;
        if (formTarget === "processForm" && isAction) {delay = 4000;}
      }
      nonClickedSubmitElements.forEach((el) => el.classList.add("tempDisabled"));
    }

    await refreshScreenLog(undefined, true);

    setTimeout(() => {
      clickTarget.classList.replace("depress", "inert");
      nonClickedSubmitElements.forEach((el) => el.classList.remove("tempDisabled"));
    }, delay);
  };
});