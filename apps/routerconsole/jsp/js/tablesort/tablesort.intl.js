import Tablesort from './tablesort.js';

const collator = Intl.Collator();
Tablesort.extend("intl", _ => false, (a,b) => collator.compare(b, a));

export default Tablesort;
