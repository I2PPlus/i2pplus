/* I2P+ I2PSnark convertTooltips.js */
/* Convert title attributes to data-tooltips for additional styling */
/* Author: dr|z3d */

(() => {
  function convertTooltip(selector) {
    const elements = document.querySelectorAll(selector);
    if (!elements.length) {return;}
    elements.forEach((element) => {
      let tooltipContent = element.getAttribute("title");
      tooltipContent = tooltipContent.replace(/^(.*)\s•\s(.*)$/, '• $1\n• $2');
      element.setAttribute("data-tooltip", tooltipContent);
      element.removeAttribute("title");
      element.classList.add("barTooltip");
    });
  }

  const styleElement = document.createElement("style");
  styleElement.innerHTML =
    '.barTooltip{position:relative}' +
    '.barTooltip::before,.barTooltip::after{position:absolute;left:50%;opacity:0;pointer-events:none;transition:ease .2s opacity .2s}' +
    '.barTooltip::before{padding:5px 10px;position:absolute;bottom:120%;z-index:999;line-height:1.3;white-space:pre;font-size:90%;color:var(--ink);border-radius:4px;background:var(--tooltip);transform:translateX(-50%);content:attr(data-tooltip)}' +
    '.barTooltip::after{height:0;width:0;bottom:calc(120% - 6px);z-index:998;border:6px solid var(--tooltip);content:"";transform:translateX(-50%) rotate(45deg)}'+
    '.barTooltip:hover::before,.barTooltip:hover::after,txd:hover .barTooltip::before,.txd:hover .barTooltip::after{opacity:1}' +
    '.barTooltip:not(:hover)::before,.barTooltip:not(:hover)::after{transition:ease .15s opacity .15s}';
  document.head.appendChild(styleElement);

  convertTooltip(".tx[title], .barComplete[title]");

  document.body.addEventListener('DOMNodeInserted', () => convertTooltip(".tx[title], .barComplete[title]"));
})();