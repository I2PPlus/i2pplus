/**
 * @module netdb
 * @description Handles auto-refresh and table sorting for the /netdb pages,
 * including country list, router entries, and leaseset views.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from "/js/refreshElements.js";

(() => {
  document.addEventListener("DOMContentLoaded", () => {
    const countries = document.getElementById("netdbcountrylist");
    const hasRI = document.querySelector(".netdbentry");
    const hasLS = document.querySelector(".leaseset");

    if (!countries && !hasLS && !hasRI) { return; }

    const REFRESH_INTERVAL_SHORT = 10_000;
    const REFRESH_INTERVAL = 15_000;
    const url = window.location.href;
    const ccsorter = countries ? new Tablesort(countries, { descending: true }) : null;

    if (countries) {
      countries.addEventListener("beforeSort", () => {
        progressx.show(theme);
        progressx.progress(0.5);
      });
      countries.addEventListener("afterSort", () => progressx.hide());
    }

    /**
     * Initializes auto-refresh based on the current view type (countries, RI, or LS).
     * @function initRefresh
     * @returns {void}
     */
    const initRefresh = () => {
      if (countries) { refreshElements("#netdboverview table tbody", url, REFRESH_INTERVAL_SHORT); }
      else if (hasRI) { refreshElements(".netdbentry", url, REFRESH_INTERVAL); }
      else if (hasLS) { refreshElements(".leaseset", url, REFRESH_INTERVAL); }
    };

    document.addEventListener("refreshComplete", () => ccsorter?.refresh());

    initRefresh();
  });
})();