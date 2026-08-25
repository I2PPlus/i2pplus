/**
 * @file loader.mjs - Entry-point registration for Node module customization hooks.
 *
 * Some scripts reference sibling files that only exist after the ant build copies
 * them into the war (e.g. i2psnark's refreshTorrents.js imports ./morphdom.js, which
 * lives in apps/routerconsole/jsp/js in the source tree). resolveHook.mjs maps those
 * build-time copies back to their source locations so the raw tree is fully
 * importable. Extend BUILD_TIME_COPIES there as new cases appear.
 *
 * Loaded via: node --import .../helpers/loader.mjs --test ...
 * Requires Node >= 20.6 (module.register hooks).
 *
 * @license AGPL3 or later
 */

import { register } from "node:module";

register("./resolveHook.mjs", import.meta.url);
