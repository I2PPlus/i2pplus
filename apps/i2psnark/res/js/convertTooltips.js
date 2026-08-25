/**
 * @module convertTooltips
 * @file convertTooltips.js - Convert title attributes to data-tooltips for enhanced styling.
 * @description Converts native browser title attributes on progress bar and torrent status
 * elements into custom data-tooltip attributes with CSS-based tooltip styling. Uses a
 * MutationObserver to handle dynamically added elements. Provides styled pseudo-element
 * tooltips with arrow indicators.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {formatTooltipText} from "./uiLogic.js";

/** Elements whose native title is restyled as a data-tooltip */
const TOOLTIP_SELECTOR = ".tx[title], .barComplete[title]";

(() => {

  /**
   * @function convertOne
   * @description Converts a single matching element: title to a reformatted
   * data-tooltip attribute, removes the native title, adds the barTooltip class.
   *
   * @param {HTMLElement} element - element carrying a title attribute
   * @returns {void}
   */
  function convertOne(element) {
    const title = element.getAttribute("title");
    if (title === null) {return;}
    element.setAttribute("data-tooltip", formatTooltipText(title));
    element.removeAttribute("title");
    element.classList.add("barTooltip");
  }

  /**
   * @function convertTree
   * @description Converts the given node if it matches the selector, plus any
   * matching descendants. Scoped to mutation records instead of rescanning the
   * whole table, so cost scales with what actually changed rather than row count.
   *
   * @param {Node} node - added element or the target of an attribute mutation
   * @returns {void}
   */
  function convertTree(node) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE) {return;}
    if (node.matches(TOOLTIP_SELECTOR)) {convertOne(node);}
    node.querySelectorAll(TOOLTIP_SELECTOR).forEach(convertOne);
  }

  const styleElement = document.createElement("style");
  styleElement.innerHTML =
    '.barTooltip{position:relative}' +
    '.barTooltip:hover,#snarkTbody:hover .barTooltip{overflow:visible}' +
    '.barTooltip::before,.barTooltip::after{position:absolute;left:50%;opacity:0;box-shadow:2px 2px 2px #0004;pointer-events:none;transition:ease .2s opacity .2s}' +
    '.barTooltip::before{padding:5px 10px;position:absolute;bottom:120%;z-index:999;line-height:1.3;white-space:pre;font-size:90%;font-weight:500;color:var(--ink);border-radius:4px;background:var(--tooltip);transform:translateX(-50%);content:attr(data-tooltip)}' +
    '.barTooltip::after{bottom:calc(120% - 6px);z-index:999;border:6px solid var(--tooltip);content:"";transform:translateX(-50%) rotate(45deg)}'+
    '.txd .barTooltip::before{bottom:calc(128%)}' +
    '.txd .barTooltip::after{bottom:calc(128% - 6px)}' +
    '.barTooltip:hover::before,.barTooltip:hover::after,.txd:hover .barTooltip::before,.txd:hover .barTooltip::after{opacity:1}';
  document.head.appendChild(styleElement);

  convertAll();

  let convertPending = false;

  /**
   * Set of candidate nodes accumulated between animation frames. Coalesces the
   * observer's per-record callbacks into one conversion pass per frame.
   */
  const pendingNodes = new Set();

  /**
   * @function queueConvert
   * @description Collects nodes from mutation records and schedules a single
   * conversion pass per animation frame.
   *
   * @param {MutationRecord[]} records - mutation batch from the observer
   * @returns {void}
   */
  function queueConvert(records) {
    for (const record of records) {
      if (record.type === "attributes") {
        pendingNodes.add(record.target);
      } else if (record.type === "childList") {
        record.addedNodes.forEach(node => pendingNodes.add(node));
      }
    }
    if (convertPending) {return;}
    convertPending = true;
    requestAnimationFrame(() => {
      convertPending = false;
      pendingNodes.forEach(convertTree);
      pendingNodes.clear();
    });
  }

  function convertAll() {
    document.querySelectorAll(TOOLTIP_SELECTOR).forEach(convertOne);
  }

  const observer = new MutationObserver(queueConvert);

  const targetNode = document.querySelector('#torrents tbody');
  if (targetNode) {
    // attributes/title matters: morphdom updates a bar's title in place when its
    // progress changes, without any childList activity for that element.
    observer.observe(targetNode, { childList: true, subtree: true, attributes: true, attributeFilter: ["title"] });
  }
})();
