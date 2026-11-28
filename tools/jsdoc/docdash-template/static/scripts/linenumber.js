/**
 * @module docdash-linenumber
 * @description Numbers the source-view pages jsdoc generates: each line gets an id of
 * the form lineN, and the one named by the URL fragment is marked selected.
 * @see {@link https://github.com/clenemt/docdash}
 * @license Apache-2.0 (upstream docdash)
 */
/*global document */
(function() {
    const source = document.getElementsByClassName('prettyprint source linenums');
    let i = 0;
    let lineNumber = 0;
    let lineId;
    let lines;
    let totalLines;
    let anchorHash;

    if (source && source[0]) {
        anchorHash = document.location.hash.substring(1);
        lines = source[0].getElementsByTagName('li');
        totalLines = lines.length;

        for (; i < totalLines; i++) {
            lineNumber++;
            lineId = 'line' + lineNumber;
            lines[i].id = lineId;
            if (lineId === anchorHash) {
                lines[i].className += ' selected';
            }
        }
    }
})();
