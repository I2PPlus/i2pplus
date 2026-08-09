/**
 * @module transit
 * @description Thin wrapper for /transit page using shared transit-common module.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {initTransit} from "/js/transit-common.js";
initTransit({fetchUrl: "/transit"});