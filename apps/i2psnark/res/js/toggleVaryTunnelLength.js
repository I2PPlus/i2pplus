/**
 * @module toggleVaryTunnelLength
 * @file toggleVaryTunnelLength.js - Configure tunnel length variance in the I2PSnark UI.
 * @description Provides a toggle mechanism for inbound and outbound tunnel length variance
 * by manipulating the i2cpOpts input field. When a checkbox is checked, sets
 * lengthVariance=1; when unchecked, sets it back to 0. Appends the option if not present.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @function toggleVaryTunnelLength
 * @description Registers a change listener on the specified checkbox that toggles the
 * tunnel length variance between 0 and 1 in the i2cpOpts input field. Handles both
 * replacement of existing variance values and appending new ones.
 * @param {string} checkboxId - The id of the checkbox element ("varyInbound" or "varyOutbound").
 * @param {string} string - The I2CP option string to append if no existing variance value is found.
 * @returns {void}
 * @example
 * toggleVaryTunnelLength("varyInbound", " inbound.lengthVariance=1");
 */
function toggleVaryTunnelLength(checkboxId, string) {
  const checkbox = document.getElementById(checkboxId);
  const input = document.getElementById("i2cpOpts");

  checkbox.addEventListener("change", () => {
    if (checkbox.checked) {
      if (checkboxId === "varyInbound" && input.value.includes("inbound.lengthVariance=0")) {
        input.value = input.value.replace("inbound.lengthVariance=0", "inbound.lengthVariance=1");
      } else if (checkboxId === "varyOutbound" && input.value.includes("outbound.lengthVariance=0")) {
        input.value = input.value.replace("outbound.lengthVariance=0", "outbound.lengthVariance=1");
      } else {
        input.value += ` ${string}`;
      }
    } else {
      if (checkboxId === "varyInbound" && input.value.includes("inbound.lengthVariance=1")) {
        input.value = input.value.replace("inbound.lengthVariance=1", "inbound.lengthVariance=0");
      } else if (checkboxId === "varyOutbound" && input.value.includes("outbound.lengthVariance=1")) {
        input.value = input.value.replace("outbound.lengthVariance=1", "outbound.lengthVariance=0");
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", () => {
  toggleVaryTunnelLength("varyInbound", " inbound.lengthVariance=1");
  toggleVaryTunnelLength("varyOutbound", " outbound.lengthVariance=1");
});