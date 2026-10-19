/**
 * @module samDebug
 * @description Auto-refreshes the SAM tab of the /debug page: morphs the
 * bridge status rings and the session table every ten seconds. The loop is
 * skipped entirely when the SAM bridge is not running.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import {refreshElements} from "/js/refreshElements.js";

const REFRESH_INTERVAL = 10 * 1000;

if (document.getElementById("samstats")) {
  refreshElements("#samstats, #samdebug", "/debug?d=7", REFRESH_INTERVAL);
}
