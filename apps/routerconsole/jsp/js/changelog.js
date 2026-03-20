/**
 * @module changelog
 * @description Prettifies the in-console changelog by wrapping content blocks
 * in span elements and styling bullet points and star markers.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Finds the changelog pre element, splits content by blank lines,
 * and wraps each block in a lazy-loading span with HTML escaping and marker styling.
 * @function spanify
 * @returns {void}
 */
(function spanify() {
    const content = document.querySelector("#changelog pre");
    if (!content) {return;}
    const blocks = content.textContent.trim().split("\n\n");
    const wrappedBlocks = blocks.map(function(block) {
      let transformedBlock = block.replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/ \* /g, "<b class=star>*</b> ").replace(/   - /g, "<b class=bullet>-</b> ");
      return "<span class=lazy>" + transformedBlock + "</span>";
    });
    const wrappedContent = wrappedBlocks.join("\n\n");
    content.innerHTML = wrappedContent;
})();
