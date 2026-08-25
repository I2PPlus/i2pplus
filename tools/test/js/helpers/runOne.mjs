/**
 * @file runOne.mjs - Evaluates exactly one project JS file under the browser shim.
 *
 * Spawned by dom/importAll.test.js in a fresh child process per target file so every
 * module sees pristine globals, nothing leaks between files, and a wedged import
 * (top-level await on a never-settling shim promise, say) dies by timeout instead of
 * hanging the whole suite.
 *
 * Exit codes: 0 = parsed, resolved, evaluated; 1 = any error; 2 = usage.
 *
 * @license AGPL3 or later
 */

import { pathToFileURL } from "node:url";
import { installBrowserShim } from "./browserShim.js";

const target = process.argv[2];
if (!target) {
  console.error("usage: runOne.mjs <file.js>");
  process.exit(2);
}

process.on("unhandledRejection", () => {});
installBrowserShim();

try {
  await import(pathToFileURL(target).href);
  console.log(`SMOKE-OK ${target}`);
} catch (error) {
  console.error(`SMOKE-FAIL ${target}`);
  console.error(error && error.stack ? error.stack : String(error));
  process.exit(1);
}
