/* I2P+ netdb.js by dr|z3d */
/* Handle refresh, table sorts on /netdb */
/* License: AGPL3 or later */

import { refreshElements } from "/js/refreshElements.js";
import Tablesort from "/js/tablesort/tablesort.js";
import "/js/tablesort/tablesort.number.js";

(() => {
  document.addEventListener("DOMContentLoaded", () => {
    const countries = document.getElementById("netdbcountrylist");
    const hasRI = document.querySelector(".netdbentry");
    const hasLS = document.querySelector(".leaseset");

    if (!countries && !hasLS && !hasRI) return;

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

    const initRefresh = () => {
      if (countries) refreshElements("#netdboverview table tbody", url, REFRESH_INTERVAL_SHORT);
      else if (hasRI) refreshElements(".netdbentry", url, REFRESH_INTERVAL);
      else if (hasLS) refreshElements(".leaseset", url, REFRESH_INTERVAL);
    };

    document.addEventListener("refreshComplete", () => ccsorter?.refresh());

    initRefresh();
  });
})();