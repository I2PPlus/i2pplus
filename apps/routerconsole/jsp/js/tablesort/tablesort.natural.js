/*
  Natural Sort
  https://github.com/etrinh/tablesort/blob/6a47651de804240115d43c1b48156c6b1ebe096a/src/sorts/tablesort.natural.js
  https://stackoverflow.com/questions/2802341/javascript-natural-sort-of-alphanumerical-strings
*/

;(function() {
  Tablesort.extend("natural",
    item => item !== null && item.trim() !== "",
    (a, b) => {
      if (a === b) return 0;
      if (a === "unknown" && b !== "unknown") return 1;
      if (b === "unknown" && a !== "unknown") return -1;
      if (a === null && b !== null) return 1;
      if (b === null && a !== null) return -1;

      const aParts = a.toLowerCase().match(/(\.\d+)|(\d+(\.\d+)?)|([^\d.]+)/g) || [];
      const bParts = b.toLowerCase().match(/(\.\d+)|(\d+(\.\d+)?)|([^\d.]+)/g) || [];
      const minLength = Math.min(aParts.length, bParts.length);

      for (let i = 0; i < minLength; i++) {
        const aPart = aParts[i], bPart = bParts[i];
        const aNum = parseFloat(aPart), bNum = parseFloat(bPart);

        if (!isNaN(aNum) && !isNaN(bNum)) {
          if (aNum !== bNum) return bNum - aNum;
        } else {
          if (aPart !== bPart) return aPart < bPart ? -1 : 1;
        }
      }
      return bParts.length - aParts.length;
    }
  );
})();