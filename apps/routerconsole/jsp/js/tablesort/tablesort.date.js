import Tablesort from './tablesort.js';

const parseDate = (date) => {
  const d = date.replace(/-/g, '/').replace(/(\d{1,2})\/(\d{1,2})\/(\d{2,4})/, '$3-$2-$1');
  const time = new Date(d).getTime();
  return isNaN(time) ? -1 : time;
};

Tablesort.extend('date',
  item => {
    const hasDayName = /(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\.?\,?\s*/i.test(item);
    const hasNumericDate = /\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}/.test(item);
    const hasMonthName = /(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)/i.test(item);
    return (hasDayName || hasNumericDate || hasMonthName) && parseDate(item) !== -1;
  },
  (a, b) => parseDate(b.toLowerCase()) - parseDate(a.toLowerCase())
);

export default Tablesort;
