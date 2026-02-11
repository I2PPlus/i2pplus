import Tablesort from './tablesort.js';

Tablesort.extend(
  'dotsep',
  item => /^(\d+\.)+\d+$/.test(item),
  (a, b) => {
    const aParts = a.split('.'), bParts = b.split('.');
    for (let i = 0; i < aParts.length; i++) {
      const ai = parseInt(aParts[i], 10), bi = parseInt(bParts[i], 10);
      if (ai === bi) continue;
      return ai > bi ? -1 : 1;
    }
    return 0;
  }
);

export default Tablesort;
