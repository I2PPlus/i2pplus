/**
 * @module formsubmit
 * @description Handles form submission with progress bar display and automatic
 * page element refresh via XHR on configuration pages (update, reseed).
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  const processForm = document.getElementById("processForm");
  const statusNews = document.getElementById("statusNews");
  const configReseed = document.getElementById("config_reseed");
  let formSubmit = false;

  /**
   * Refreshes a specific DOM element by fetching the URL and replacing its innerHTML.
   * @function refresh
   * @param {string} elementId - The ID of the element to refresh
   * @param {string} url - The URL to fetch updated content from
   * @returns {void}
   */
  function refresh(elementId, url) {
    const element = document.getElementById(elementId);
    const xhrRefresh = new XMLHttpRequest();
    xhrRefresh.open("GET", url, true);
    xhrRefresh.responseType = "document";
    xhrRefresh.onreadystatechange = function() {
      if (xhrRefresh.readyState === XMLHttpRequest.DONE) {
        if (xhrRefresh.status === 200) {
          const elementResponse = xhrRefresh.responseXML.getElementById(elementId);
          element.innerHTML = elementResponse.innerHTML;
        }
        progressx.hide();
      }
    };
    xhrRefresh.send();
  }

  /**
   * Sets up form submission and iframe load listeners to trigger content refresh.
   * @function setupFormListeners
   * @param {string} formId - The ID of the form element
   * @param {string} elementId - The ID of the element to refresh after submission
   * @param {string} url - The URL to fetch refreshed content from
   * @returns {void}
   */
  function setupFormListeners(formId, elementId, url) {
    const form = document.getElementById(formId);

    form.addEventListener("submit", function(event) {
      progressx.show(theme);
      formSubmit = true;
    });

    processForm.addEventListener("load", function() {
      if (formSubmit) {
        refresh(elementId, url);
        formSubmit = false;
      }
    });
  }

  window.addEventListener("DOMContentLoaded", function() {
    progressx.hide();
    if (statusNews) {
      setupFormListeners("form_updates", "statusNews", "/configupdate");
    }
    if (configReseed) {
      setupFormListeners("form_reseed", "config_reseed", "/configreseed");
    }
  });

})();