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
import "./pure/routerControlState.test.js";
import "./pure/logTags.test.js";
import "./pure/suiteCoverage.test.js";
import "./pure/navigationJav8.test.js";
import "./dom/refreshPayload.test.js";
import "./dom/refreshElementsPositionKey.test.js";
import "./dom/esmScriptTags.test.js";
import "./dom/graphTooltip.test.js";
import "./dom/toggleDebug.test.js";
import "./dom/routerControl.test.js";
import "./dom/refreshLogsLinkify.test.js";
import "./dom/refreshLogsFraming.test.js";
import "./dom/vdomParser.test.js";
import "./dom/morphdom.test.js";
import "./dom/jdenticon.test.js";
import "./dom/docdashNav.test.js";
import "./dom/progressx.test.js";
import "./dom/iframeResizer.test.js";

await import("./dom/importAll.test.js");
