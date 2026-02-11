import Tablesort from './tablesort.js';

const base = 1024;
const suffix2num = s => {
  switch ((s[0] || '').toLowerCase()) {
    case 'k': return Math.pow(base, 1);
    case 'm': return Math.pow(base, 2);
    case 'g': return Math.pow(base, 3);
    case 't': return Math.pow(base, 4);
    case 'p': return Math.pow(base, 5);
    case 'e': return Math.pow(base, 6);
    case 'z': return Math.pow(base, 7);
    case 'y': return Math.pow(base, 8);
    default: return 1;
  }
};
const cleanNumber = i => i.replace(/[^\-?0-9.]/g, '');
const filesize2num = f => {
  const m = f.match(/^(\d+(\.\d+)?) ?((K|M|G|T|P|E|Z|Y|B$)i?B?)$/i);
  if (!m) return 0;
  const num = parseFloat(cleanNumber(m[1])), suf = m[3];
  return num * suffix2num(suf);
};
const compareNumber = (a, b) => {
  a = isNaN(a) ? 0 : a;
  b = isNaN(b) ? 0 : b;
  return a - b;
};

Tablesort.extend('filesize',
  item => /^\d+(\.\d+)? ?(K|M|G|T|P|E|Z|Y|B$)i?B?$/i.test(item),
  (a, b) => compareNumber(filesize2num(b), filesize2num(a))
);

export default Tablesort;
