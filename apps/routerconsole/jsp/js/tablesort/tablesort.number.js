(function () {
  // Cleans numeric strings without breaking scientific notation or decimals
  const cleanNumber = (val) => {
    if (typeof val !== 'string') return '';
    // Allow digits, minus, dot, E/e for scientific notation, and %
    return val.replace(/[^-0-9.Ee%]/g, '');
  };

  // Compares two numeric values, handling NaN gracefully
  const compareNumber = (a, b) => {
    const numA = parseFloat(a);
    const numB = parseFloat(b);

    if (isNaN(numA) && isNaN(numB)) return 0;
    if (isNaN(numA)) return 1;  // b comes first
    if (isNaN(numB)) return -1; // a comes first
    return numB - numA;         // descending sort
  };

  // Register the numeric sorter with Tablesort
  Tablesort.extend('number',
    // Pattern: detects strings that contain valid numbers
    function (item) {
      const cleaned = cleanNumber(item);
      return cleaned !== '' && !isNaN(parseFloat(cleaned)) && isFinite(cleaned);
    },
    // Sort function: compares cleaned numeric values
    function (a, b) {
      return compareNumber(cleanNumber(a), cleanNumber(b));
    }
  );
})();
