(function() {
  // Removes all but digits, minus, dot, and question mark from string
  const cleanNumber = i => i.replace(/[^\-?0-9.]/g, '');

  // Parses strings to floats and compares numerically, treating NaN as 0
  const compareNumber = (a, b) => {
    a = parseFloat(a);
    b = parseFloat(b);
    a = isNaN(a) ? 0 : a;
    b = isNaN(b) ? 0 : b;
    return a - b;
  };

  Tablesort.extend("number",
    // Matches numbers; allows optional prefixed/suffixed currency symbols and percent signs
    item =>
      /^[-+]?[£\x24Û¢´€]?\d+\s*([,\.]\d{0,2})/.test(item) ||  // Prefixed currency
      /^[-+]?\d+\s*([,\.]\d{0,2})?[£\x24Û¢´€]/.test(item) ||  // Suffixed currency
      /^[-+]?(\d)*-?([,\.]){0,1}-?(\d)+([E,e][\-+][\d]+)?%?$/.test(item),  // Plain number
    (a, b) => {
      a = cleanNumber(a);
      b = cleanNumber(b);
      return compareNumber(b, a);
    }
  );
})();
