import Tablesort from './tablesort.js';

const monthNames = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
Tablesort.extend(
  'monthname',
  item => /January|February|March|April|May|June|July|August|September|October|November|December/i.test(item),
  (a, b) => monthNames.indexOf(b) - monthNames.indexOf(a)
);

export default Tablesort;
