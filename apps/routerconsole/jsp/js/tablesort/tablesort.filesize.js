/* Filesizes. e.g. '5.35 K', '10 MB', '12.45 GB', or '4.67 TiB' */

;(function(){
  const compareNumber = function(a, b) {
    a = parseFloat(a);
    b = parseFloat(b);
    a = isNaN(a) ? 0 : a;
    b = isNaN(b) ? 0 : b;
    return a - b;
  },

  cleanNumber = function(i) {return i.replace(/[^\-?0-9.]/g, '');},

  suffix2num = function(suffix) {
    suffix = suffix.toLowerCase();
    const base = 1024;
    switch(suffix[0]) {
      case 'k': return Math.pow(base, 1);
      case 'm': return Math.pow(base, 2);
      case 'g': return Math.pow(base, 3);
      case 't': return Math.pow(base, 4);
      case 'p': return Math.pow(base, 5);
      case 'e': return Math.pow(base, 6);
      case 'z': return Math.pow(base, 7);
      case 'y': return Math.pow(base, 8);
      default: return base;
    }
  },

  // Converts filesize to bytes, assumes 1024 bytes regardless of KB/KiB
  filesize2num = function(filesize) {
    const matches = filesize.match(/^(\d+(\.\d+)?) ?((K|M|G|T|P|E|Z|Y|B$)i?B?)$/i);
    if (!matches) {return 0;}
    const num  = parseFloat(cleanNumber(matches[1])), suffix = matches[3];
    return num * suffix2num(suffix);
  };

  Tablesort.extend('filesize', function(item) {
    return /^\d+(\.\d+)? ?(K|M|G|T|P|E|Z|Y|B$)i?B?$/i.test(item);
  }, function(a, b) {
    a = filesize2num(a);
    b = filesize2num(b);
    return compareNumber(b, a);
  });

}());