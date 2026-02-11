import Tablesort from './tablesort.js';

const cleanNumber = (val) => {
  if (typeof val !== 'string') return '';
  return val.replace(/[^-0-9.Ee%]/g, '');
};

const compareNumber = (a, b) => {
  const numA = parseFloat(a);
  const numB = parseFloat(b);

  if (isNaN(numA) && isNaN(numB)) return 0;
  if (isNaN(numA)) return 1;
  if (isNaN(numB)) return -1;
  return numB - numA;
};

Tablesort.extend('number',
  function (item) {
    const cleaned = cleanNumber(item);
    return cleaned !== '' && !isNaN(parseFloat(cleaned)) && isFinite(cleaned);
  },
  function (a, b) {
    return compareNumber(cleanNumber(a), cleanNumber(b));
  }
);

export default Tablesort;
