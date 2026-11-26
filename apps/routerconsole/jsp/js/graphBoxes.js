/**
 * @module graphBoxes
 * @file graphBoxes.js - How big a graph's box on the page is, and where that is written.
 * @description The graphs page gives every .graphContainer the same size, so that two dozen
 * graphs line up as a grid rather than as a ragged set of tiles. It does that with one
 * stylesheet rule naming a width and a height, measured from the pictures the router actually
 * drew - the tallest of them, because a box smaller than the picture inside it clips that
 * picture, and a tile that carries a legend for two rates draws taller than the plain stats
 * beside it at the same plot size.
 *
 * Two scripts need that rule: the page's own layout script, when the graphs first arrive, and
 * the gesture script, when a ctrl-drag or a ctrl-roll changes the size of the pictures. The
 * measurement and the rule live here so both write the same one.
 *
 * The picture leads and the box follows. An image of its own size is a box the browser scales
 * a picture into, so this module takes that off a graph being resized: the router's px is what
 * gets drawn, and the container is sized from the pictures, never the reverse.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

/**
 * What the box adds around the picture - its border and padding - in px. The theme's own
 * padding is what this has to clear, and the grid has used 4 since the rule was written.
 */
const FRAME_PX = 4;

/**
 * The picture a graph image holds, in rendered px.
 *
 * <p>This is the size the router drew, not the size the graph's src asked for: the src sizes
 * the plot, and the picture carries a title, an axis and, unless the options hide it, a
 * legend. An image that has not come back yet has no size to report.
 *
 * @function graphBox
 * @param {HTMLImageElement} img the graph image
 * @returns {?{width: number, height: number}} the picture's size, null until it is drawn
 */
export function graphBox(img) {
  return img.naturalWidth > 0 && img.naturalHeight > 0
         ? {width: img.naturalWidth, height: img.naturalHeight}
         : null;
}

/**
 * The biggest picture among a set of graphs - the box every container on the page has to hold.
 *
 * <p>Widest and tallest are taken independently: the grid pins one width and one height, and a
 * box that is short for one graph and narrow for another clips both.
 *
 * @function tallestBox
 * @param {Array<HTMLImageElement>|NodeList} imgs the graphs to measure
 * @returns {?{width: number, height: number}} the box, null while none of them is drawn
 */
export function tallestBox(imgs) {
  let width = 0;
  let height = 0;
  let drawn = false;
  for (const img of imgs) {
    const box = graphBox(img);
    if (!box) {continue;}
    drawn = true;
    width = Math.max(width, box.width);
    height = Math.max(height, box.height);
  }
  return drawn ? {width: width, height: height} : null;
}

/**
 * Writes the rule that pins every graph box on the page to one size.
 *
 * @function pinGraphBoxes
 * @param {HTMLElement} style the style element the page's layout rule lives in
 * @param {?{width: number, height: number}} box the picture every container has to hold
 * @returns {boolean} true if a size was written, false if there was nothing to pin
 */
export function pinGraphBoxes(style, box) {
  if (!style || !box) {return false;}
  const width = box.width + FRAME_PX;
  const height = box.height + FRAME_PX;
  const css = ".graphContainer { width: " + width + "px; height: " + height + "px; }";
  if (style.textContent !== css) {style.textContent = css;}
  return true;
}

/**
 * Lets a graph picture take its own size.
 *
 * <p>A width or height attribute on the image is a box the browser scales the picture into,
 * so one left over from the size the page was first built at squashes every picture drawn
 * since: the container grows, the picture inside it does not, and the graph comes out out of
 * proportion. The picture's own px is the size it is drawn at and the container rule follows
 * that, never the other way round.
 *
 * @function freeGraphSize
 * @param {HTMLImageElement} img the graph image
 * @returns {boolean} true if a size was taken off it
 * @since 0.9.72+
 */
export function freeGraphSize(img) {
  let freed = false;
  if (img.getAttribute("width") !== null) {img.removeAttribute("width"); freed = true;}
  if (img.getAttribute("height") !== null) {img.removeAttribute("height"); freed = true;}
  return freed;
}
