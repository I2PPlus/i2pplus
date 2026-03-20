/**
 * @module togglePanels
 * @file I2P+ SusiDNS panel toggler.
 * Provides show/hide functionality for the "Add New Host" and "Import Hosts"
 * panels, including scroll-lock and iframe-aware behavior.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function () {
  /**
   * Initialises panel toggle handlers on DOM load. Detects the active theme,
   * wires up click listeners on toggle buttons, and manages CSS classes and
   * scroll behavior.
   *
   * @listens DOMContentLoaded
   */
  document.addEventListener("DOMContentLoaded", function () {
    const darkTheme = document.body.classList.contains("dark");
    const lightTheme = document.body.classList.contains("light");
    if (!darkTheme && !lightTheme) {return;}

    const addContainer = document.getElementById("add");
    const addReset = document.querySelector("#add input[type=reset]");
    const addSubmits = document.querySelectorAll("#addDestForm input[type=submit]");
    const addTextInputs = document.querySelectorAll("#add input[type=text]");
    const addToggle = document.getElementById("addNewDest");
    const importContainer = document.getElementById("import");
    const importReset = document.querySelector("#import input[type=reset]");
    const importSubmit = document.querySelector("#importHostsForm input[type=submit]");
    const importToggle = document.getElementById("importFromFile");
    const messages = document.querySelector("#messages p");
    const isIframed = document.documentElement.classList.contains("iframed") || window.top !== window.parent.top;

    [addContainer, importContainer].forEach((container) => {
      if (container) {container.hidden = true;}
    });

    addTextInputs.forEach(input => {input.value = "";});

    [addToggle, importToggle].forEach((button) => {
      if (button) {
        button.removeAttribute("href");
        button.removeAttribute("style");
        button.hidden = false;
      }
    });

    /**
     * Shows one panel container and hides the other, adjusting page
     * layout and scroll behavior accordingly.
     *
     * @function toggleVisibility
     * @param {HTMLElement|null} showContainer - The container to reveal.
     * @param {HTMLElement|null} hideContainer - The container to conceal.
     * @returns {void}
     */
    function toggleVisibility(showContainer, hideContainer) {
      if (showContainer) {showContainer.hidden = false;}
      if (hideContainer) {hideContainer.hidden = true;}
      toggleBodyClass();
      if (!showContainer.hidden) {
        document.documentElement.classList.add("noscroll");
        const page = document.getElementById("page");
        if (lightTheme) {page.style.minHeight = "230px";}
        else {page.style.minHeight = "255px";}
        if (isIframed) {
          window.parent.document.documentElement.style.overflow = "hidden";
        }
      }
    }

    /**
     * Adds or removes the "displayPanels" class on `<body>` based on the
     * visibility state of the add and import containers. Applies a short
     * delay when panels are transitioning out.
     *
     * @function toggleBodyClass
     * @returns {void}
     */
    function toggleBodyClass() {
      const shouldHide = addContainer?.classList.contains("isHidden") || importContainer?.classList.contains("isHidden");
      const hidden = addContainer.hidden && importContainer.hidden || shouldHide;
      if (!shouldHide) {document.body.classList.toggle("displayPanels", !hidden);}
      else {
        setTimeout(() => {
          document.body.classList.remove("displayPanels");
          document.documentElement.classList.remove("noscroll");
          page.style.minHeight = null;
          if (isIframed) {window.parent.document.documentElement.style.overflow = null;}
        }, 240);
      }
    }

    /**
     * Clears all text input values inside the "Add" panel and hides the
     * container with a brief CSS transition.
     *
     * @function resetAddInputs
     * @returns {void}
     */
    function resetAddInputs() {
      addTextInputs.forEach(input => {
        input.removeAttribute("value");
        input.setAttribute("value", "");
      });
      addContainer.classList.add("isHidden");
      setTimeout(() => {
        toggleBodyClass();
        addContainer.hidden = true;
        addContainer.classList.remove("isHidden");
      }, 240);
    }

    addToggle?.addEventListener("click", () =>
      toggleVisibility(addContainer, importContainer)
    );

    importToggle?.addEventListener("click", () =>
      toggleVisibility(importContainer, addContainer)
    );

    addReset?.addEventListener("click", resetAddInputs);

    importReset?.addEventListener("click", () => {
      importContainer.classList.add("isHidden");
      toggleBodyClass();
      setTimeout(() => {
        importContainer.hidden = true;
        importContainer.classList.remove("isHidden");
      }, 240);
    });

  });
})();