/* I2P+ submitform.js by dr|z3d */
/* Handle refresh and progress bar on form submission on configuration pages */
/* License: AGPL3 or later */

(function() {
  const processForm = document.getElementById("processForm");
  const statusNews = document.getElementById("statusNews");
  const configReseed = document.getElementById("config_reseed");
  let formSubmit = false;

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

  function setupFormListeners(formId, elementId, url) {
    const form = document.getElementById(formId);

    form.addEventListener("submit", function(event) {
      progressx.show(theme);
      formSubmit = true;
      form.target = "processForm";
      form.submit();
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