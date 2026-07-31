/**
 * @module transitfast
 * @description Thin wrapper for /transitfast page using shared transit-common module.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {initTransit} from "/js/transit-common.js";
initTransit({fetchUrl: "/transitfast"});