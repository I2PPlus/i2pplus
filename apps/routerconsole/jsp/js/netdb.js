/* I2P+ netdb.js by dr|z3d */
/* Handle refresh, table sorts on /netdb */
/* License: AGPL3 or later */

(function() {
  const countries = document.getElementById("netdbcountrylist");
  const REFRESH_INTERVAL = 30*1000;
  let ccsorter = countries !== null ? new Tablesort(countries, {descending: true}) : null;


  function initRefresh() {
    const url = window.location.href;
    if (!url.includes("?c") && !url.includes("?f") && !url.includes("?l") && !url.includes("?ls") && !url.includes("?n") && !url.includes("?r")) {
      setInterval(updateNetDb, REFRESH_INTERVAL);
    }
  }

  function updateNetDb() {
    const xhrnetdb = new XMLHttpRequest();
    xhrnetdb.open('GET', '/netdb', true);
    xhrnetdb.responseType = "document";
    xhrnetdb.onload = function () {
      const congestion = document.getElementById("netdbcongestion");
      const congestionResponse = xhrnetdb.responseXML.getElementById("netdbcongestion");
      const cclist = document.getElementById("cclist");
      const overview = document.getElementById("netdboverview");
      const overviewResponse = xhrnetdb.responseXML.getElementById("netdboverview");
      const tiers = document.getElementById("netdbtiers");
      const tiersResponse = xhrnetdb.responseXML.getElementById("netdbtiers");
      const transports = document.getElementById("netdbtransports");
      const transportsResponse = xhrnetdb.responseXML.getElementById("netdbtransports");
      const versions = document.getElementById("netdbversions");
      const versionsResponse = xhrnetdb.responseXML.getElementById("netdbversions");

      if (congestion !== null && congestion.innerHTML !== congestionResponse.innerHTML) {
        congestion.innerHTML = congestionResponse.innerHTML;
      }

      if (countries && cclist) {
        if (typeof ccsorter === "undefined" || ccsorter === null) {
          ccsorter = new Tablesort(countries, {descending: true});
        }
        const cclistResponse = xhrnetdb.responseXML.getElementById("cclist");
        if (cclist.innerHTML !== cclistResponse.innerHTML) {
          cclist.innerHTML = cclistResponse.innerHTML;
          ccsorter.refresh();
        }
      } else if (versions) {
        overview.innerHTML = overviewResponse.innerHTML;
      }

      if (tiers !== null && tiers.innerHTML !== tiersResponse.innerHTML) {
        tiers.innerHTML = tiersResponse.innerHTML;
      }

      if (transports !== null && transports.innerHTML !== transportsResponse.innerHTML) {
        transports.innerHTML = transportsResponse.innerHTML;
      }

      if (versions !== null && versions.innerHTML !== versionsResponse.innerHTML) {
        versions.innerHTML = versionsResponse.innerHTML;
      }
    }

    if (typeof ccsorter === "undefined" || ccsorter === null) {
      ccsorter = new Tablesort(countries, {descending: true});
    }

    if (countries) {
      ccsorter.refresh();
    }

    xhrnetdb.send();
  }

  document.addEventListener("DOMContentLoaded", initRefresh);
  window.addEventListener("DOMContentLoaded", progressx.hide);

  if (countries) {
    countries.addEventListener("beforeSort", function() {progressx.show(theme);progressx.progress(0.5);});
    countries.addEventListener("afterSort", function() {progressx.hide();});
  }
})();