/**
 * @module transit
 * @description Thin wrapper for /transit page using shared transit-common module.
 * Enables peer row count tracking for optimized refresh selector.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {initTransit} from "/js/transit-common.js";
initTransit({fetchUrl: "/transit", usePeerRowTracking: true});