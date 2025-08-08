/* I2P+ changelog.js by dr|z3d */
/* Prettify in-console changelog */
/* License: AGPL3 or later */

(function spanify() {
    const content = document.querySelector("#changelog pre");
    if (!content) {return;}
    const blocks = content.textContent.trim().split("\n\n");
    const wrappedBlocks = blocks.map(function(block) {
      let transformedBlock = block.replace(/ \* /g, "<b class=star>*</b> ").replace(/   - /g, "<b class=bullet>-</b> ");
      return "<span class=lazy>" + transformedBlock + "</span>";
    });
    const wrappedContent = wrappedBlocks.join("\n\n");
    content.innerHTML = wrappedContent;
})();
