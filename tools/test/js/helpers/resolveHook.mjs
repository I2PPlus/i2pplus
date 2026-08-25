/**
 * @file resolveHook.mjs - Module resolution hook mapping build-time-copied siblings
 * back to their source locations. See loader.mjs for how this is registered.
 *
 * @license AGPL3 or later
 */

import { join } from "node:path";
import { existsSync } from "node:fs";
import { pathToFileURL, fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
// helpers -> js -> test -> tools -> repo root
const repoRoot = join(here, "..", "..", "..", "..");

/**
 * Webroot-absolute imports ("/js/foo.js") resolve against the serving webapp's
 * docroot at runtime but have no meaning in the raw source tree; map them to the
 * owning webapp's script directory.
 */
const PREFIX_MAP = [
  { prefix: "/js/", dir: "apps/routerconsole/jsp/js/" },
  { prefix: "/i2psnark/.res/js/", dir: "apps/i2psnark/res/js/" },
  { prefix: "/i2ptunnel/js/", dir: "apps/i2ptunnel/jsp/js/" }
];

/** specifier -> source file, scoped by the importing module's directory */
const BUILD_TIME_COPIES = [
  {
    comment: "snark war build copies console morphdom.js next to res/js",
    specifier: "./morphdom.js",
    fromIncludes: "/apps/i2psnark/res/js/",
    to: pathToFileURL(join(repoRoot, "apps/routerconsole/jsp/js/morphdom.js")).href
  }
];

/**
 * Node resolve hook: maps build-time copies and webroot-absolute specifiers to
 * their raw-tree locations; defers everything else.
 *
 * @param {string} specifier - the import specifier
 * @param {Object} context - parent context (parentURL, conditions)
 * @param {Function} nextResolve - the next resolver in the chain
 * @returns {Promise<Object>} resolution ({url, shortCircuit})
 */
export async function resolve(specifier, context, nextResolve) {
  for (const entry of BUILD_TIME_COPIES) {
    if (
      specifier === entry.specifier &&
      context.parentURL &&
      context.parentURL.includes(entry.fromIncludes)
    ) {
      return { url: entry.to, shortCircuit: true };
    }
  }
  for (const entry of PREFIX_MAP) {
    if (specifier.startsWith(entry.prefix)) {
      const candidate = join(repoRoot, entry.dir, specifier.slice(entry.prefix.length));
      if (existsSync(candidate)) {
        return { url: pathToFileURL(candidate).href, shortCircuit: true };
      }
    }
  }
  return nextResolve(specifier, context);
}
