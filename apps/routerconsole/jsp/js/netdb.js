/* I2P+ netdb.js by dr|z3d */
/* Handle refresh, table sorts on /netdb */
/* License: AGPL3 or later */

(function() {
  const countries = document.getElementById("netdbcountrylist");
  if (!countries) {return;}
  const REFRESH_INTERVAL = 30*1000;
  let ccsorter = new Tablesort(countries, {descending: true});

  function initRefresh() {
    const url = window.location.href;
    const excludedParams = ["?c", "?f", "?l", "?ls", "?n", "?r"];
    if (!excludedParams.some(param => url.includes(param))) {
      setInterval(updateNetDb, REFRESH_INTERVAL);
    }
  }

  function updateNetDb() {
    const xhrnetdb = new XMLHttpRequest();
    xhrnetdb.open("GET", "/netdb", true);
    xhrnetdb.responseType = "document";
    xhrnetdb.onload = function () {
    const cclist = document.getElementById("cclist"),
      congestion = document.getElementById("netdbcongestion"),
      congestionResponse = xhrnetdb.responseXML.getElementById("netdbcongestion"),
      overview = document.getElementById("netdboverview"),
      overviewResponse = xhrnetdb.responseXML.getElementById("netdboverview"),
      tiers = document.getElementById("netdbtiers"),
      tiersResponse = xhrnetdb.responseXML.getElementById("netdbtiers"),
      transports = document.getElementById("netdbtransports"),
      transportsResponse = xhrnetdb.responseXML.getElementById("netdbtransports"),
      versions = document.getElementById("netdbversions"),
      versionsResponse = xhrnetdb.responseXML.getElementById("netdbversions");

      if (congestion && congestionResponse && congestion.innerHTML !== congestionResponse.innerHTML) {
        congestion.innerHTML = congestionResponse.innerHTML;
      }
      if (countries && cclist) {
        if (!ccsorter) { ccsorter = new Tablesort(countries, {descending: true}); }
        const cclistResponse = xhrnetdb.responseXML.getElementById("cclist");
        if (cclistResponse && cclist.innerHTML !== cclistResponse.innerHTML) {
          cclist.innerHTML = cclistResponse.innerHTML;
          ccsorter.refresh();
        }
      } else if (versions && overviewResponse && overview) {
        overview.innerHTML = overviewResponse.innerHTML;
      }
      if (tiers && tiersResponse && tiers.innerHTML !== tiersResponse.innerHTML) {
        tiers.innerHTML = tiersResponse.innerHTML;
      }
      if (transports && transportsResponse && transports.innerHTML !== transportsResponse.innerHTML) {
        transports.innerHTML = transportsResponse.innerHTML;
      }
      if (versions && versionsResponse && versions.innerHTML !== versionsResponse.innerHTML) {
        versions.innerHTML = versionsResponse.innerHTML;
      }
    };
    xhrnetdb.send();
  }

  countries.addEventListener("beforeSort", function() {progressx.show(theme);progressx.progress(0.5);});
  countries.addEventListener("afterSort", function() {progressx.hide();});

  document.addEventListener("DOMContentLoaded", initRefresh);

})();