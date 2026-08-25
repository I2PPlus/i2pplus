/**
 * @file suite.test.js - Single entry point for the i2p+ JS harness so ant can
 * invoke `node --test <this file>` without depending on directory-import behavior.
 * New suites are registered here. The smoke gate is imported dynamically because it
 * self-skips when linkedom is unavailable; the pure and DOM contract suites always
 * run (no dependencies).
 *
 * @license AGPL3 or later
 */

import "./pure/uiLogic.test.js";
import "./pure/payloadNonce.test.js";
import "./dom/refreshPayload.test.js";
import "./dom/esmScriptTags.test.js";

await import("./dom/importAll.test.js");
