/**
 * @module navigation-jav8
 * @file navigation-jav8.js - Frameset navigation for the Java 8 javadoc build.
 * @description Reads the page's query string, keeps only a value that looks like
 * one of our own .html pages, and loads it into the class frame, so a link into
 * the javadoc opens on the class the reader asked for. Copied to
 * dist/javadoc/navigation.js by the Java-8-only build target.
 * @author dr|z3d
 * @license AGPL3 or later
 */

// A top-level `let` is a lexical global, not a window property, so the frameset
// cannot read it as `top.targetPage`. That is safe here because this file supplies
// its own loadFrames() below, which reads the bare identifier, and because
// javadoc-postprocess.sh strips the JDK's inline target-page script from every
// page it rewrites. If that strip ever stops matching, the JDK's own loadFrames()
// reads a `top.targetPage` we no longer define and deep links land on "undefined".
let targetPage = "undefined";
(function() {
    let tmp = "" + window.location.search;
    if (tmp !== "" && tmp !== "undefined") {
        tmp = tmp.substring(1);
    }
    if (tmp.indexOf(":") !== -1 || (tmp !== "" && !validURL(tmp))) {
        tmp = "undefined";
    }
    targetPage = tmp;
})();

function validURL(e) {
    try { e = decodeURIComponent(e); } catch { return false; }
    let t, n, s, i, a = e.indexOf(".html");
    if (a === -1 || a !== e.length - 5) return false;
    for (n = false, s = false, i = false, a = 0; a < e.length - 5; a++) {
        t = e.charAt(a);
        if (("a" <= t && t <= "z") || ("A" <= t && t <= "Z") || t === "$" || t === "_" || t.charCodeAt(0) > 127) {
            n = true; s = true;
        } else if (("0" <= t && t <= "9") || t === "-") {
            if (!n) return false;
        } else if (t === "/" || t === ".") {
            if (!s) return false;
            n = false; s = false;
            if (t === ".") i = true;
            if (t === "/" && i) return false;
        } else {
            return false;
        }
    }
    return true;
}

function loadFrames() {
    if (targetPage !== "" && targetPage !== "undefined") {
        top.classFrame.location = targetPage;
    }
}

document.addEventListener('DOMContentLoaded', function() {
    document.body.addEventListener('click', function(e) {
        let target = e.target;
        while (target && target !== document.body) {
            const filter = target.getAttribute && target.getAttribute('data-method-filter');
            if (filter !== null && filter !== undefined) {
                e.preventDefault();
                show(parseInt(filter, 10));
                return;
            }
            target = target.parentNode;
        }
    });
});

function show(type) {
    let count = 0;
    for (let key in methods) {
        if (!methods.hasOwnProperty(key)) continue;
        const row = document.getElementById(key);
        if (row) {
            if ((methods[key] & type) !== 0) {
                row.style.display = '';
                row.className = (count++ % 2) ? rowColor : altColor;
            } else {
                row.style.display = 'none';
            }
        }
    }
    updateTabs(type);
}

/**
 * Escapes text for the tab markup built in updateTabs(), so a label or a filter
 * key can never close the anchor or its attribute.
 */
function esc(s) {
    return String(s).replace(/[&<>"]/g, function(c) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
}

function updateTabs(type) {
    for (let value in tabs) {
        if (!tabs.hasOwnProperty(value)) continue;
        const entry = tabs[value];
        if (!entry) continue;
        const sNode = document.getElementById(entry[0]);
        if (!sNode) continue;
        const spanNode = sNode.firstChild;
        if (spanNode) {
            if (Number(value) === type) {
                sNode.className = activeTableTab;
                spanNode.innerHTML = esc(entry[1]);
            } else {
                sNode.className = tableTab;
                spanNode.innerHTML = '<a href="#" data-method-filter="' + esc(value) + '">' + esc(entry[1]) + '</a>';
            }
        }
    }
}
