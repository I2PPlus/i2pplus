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
    '.barTooltip:hover,#snarkTbody:hover .barTooltip{overflow:visible}' +
    '.barTooltip::before,.barTooltip::after{position:absolute;left:50%;opacity:0;box-shadow:2px 2px 2px #0004;pointer-events:none;transition:ease .2s opacity .2s}' +
    '.barTooltip::before{padding:5px 10px;position:absolute;bottom:120%;z-index:999;line-height:1.3;white-space:pre;font-size:90%;font-weight:500;color:var(--ink);border-radius:4px;background:var(--tooltip);transform:translateX(-50%);content:attr(data-tooltip)}' +
    '.barTooltip::after{bottom:calc(120% - 6px);z-index:999;border:6px solid var(--tooltip);content:"";transform:translateX(-50%) rotate(45deg)}'+
    '.barTooltip:hover::before,.barTooltip:hover::after,txd:hover .barTooltip::before,txd:hover .barTooltip::after{opacity:1}';
  document.head.appendChild(styleElement);

  convertTooltip(".tx[title], .barComplete[title]");

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        convertTooltip(".tx[title], .barComplete[title]");
      }
    });
  });

  const targetNode = document.querySelector('#torrents tbody');
  observer.observe(targetNode, { childList: true, subtree: true });
})();