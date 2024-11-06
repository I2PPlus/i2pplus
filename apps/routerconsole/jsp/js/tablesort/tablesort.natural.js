// Natural Sort
// https://github.com/etrinh/tablesort/blob/6a47651de804240115d43c1b48156c6b1ebe096a/src/sorts/tablesort.natural.js
// https://stackoverflow.com/questions/2802341/javascript-natural-sort-of-alphanumerical-strings

;(function () {
  Tablesort.extend('natural', function (item) {
    return item !== null && item.trim() !== '';
  }, function (a, b) {
    if (a === b) {return 0;}
    if (a === 'unknown' && b !== 'unknown') {return 1;}
    if (b === 'unknown' && a !== 'unknown') {return -1;}
    if (a === null && b !== null) {return 1;}
    if (b === null && a !== null) {return -1;}
    let aParts = a.toLowerCase().match(/(\.\d+)|(\d+(\.\d+)?)|([^\d.]+)/g);
    let bParts = b.toLowerCase().match(/(\.\d+)|(\d+(\.\d+)?)|([^\d.]+)/g);
    if (!bParts) {return -1;}
    let aLength = aParts?.length;
    let bLength = bParts?.length;
    let minLength = Math.min(aLength, bLength);
    for (let i = 0; i < minLength; i++) {
      let aPart = aParts[i];
      let bPart = bParts[i];
      let aNum = parseFloat(aPart);
      let bNum = parseFloat(bPart);
      if (!isNaN(aNum) && !isNaN(bNum)) {
        if (aNum !== bNum) {return bNum - aNum;}
      } else {
        if (aPart !== bPart) {return aPart < bPart ? -1 : 1;}
      }
    }
    return bLength - aLength;
  });

})();