var targetPage = "undefined";
(function() {
    var tmp = "" + window.location.search;
    if (tmp != "" && tmp != "undefined") {
        tmp = tmp.substring(1);
    }
    if (tmp.indexOf(":") != -1 || (tmp != "" && !validURL(tmp))) {
        tmp = "undefined";
    }
    targetPage = tmp;
})();

function validURL(e) {
    try { e = decodeURIComponent(e); } catch { return false; }
    var t, n, s, i, a = e.indexOf(".html");
    if (a == -1 || a != e.length - 5) return false;
    for (n = false, s = false, i = false, a = 0; a < e.length - 5; a++) {
        t = e.charAt(a);
        if (("a" <= t && t <= "z") || ("A" <= t && t <= "Z") || t == "$" || t == "_" || t.charCodeAt(0) > 127) {
            n = true; s = true;
        } else if (("0" <= t && t <= "9") || t == "-") {
            if (!n) return false;
        } else if (t == "/" || t == ".") {
            if (!s) return false;
            n = false; s = false;
            if (t == ".") i = true;
            if (t == "/" && i) return false;
        } else {
            return false;
        }
    }
    return true;
}

function loadFrames() {
    if (targetPage != "" && targetPage != "undefined") {
        top.classFrame.location = targetPage;
    }
}

document.addEventListener('DOMContentLoaded', function() {
    document.body.addEventListener('click', function(e) {
        var target = e.target;
        while (target && target !== document.body) {
            var filter = target.getAttribute && target.getAttribute('data-method-filter');
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
    var count = 0;
    for (var key in methods) {
        if (!methods.hasOwnProperty(key)) continue;
        var row = document.getElementById(key);
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

function updateTabs(type) {
    for (var value in tabs) {
        if (!tabs.hasOwnProperty(value)) continue;
        var entry = tabs[value];
        if (!entry) continue;
        var sNode = document.getElementById(entry[0]);
        if (!sNode) continue;
        var spanNode = sNode.firstChild;
        if (spanNode) {
            if (value == type) {
                sNode.className = activeTableTab;
                spanNode.innerHTML = entry[1];
            } else {
                sNode.className = tableTab;
                spanNode.innerHTML = '<a href="#" data-method-filter="' + value + '">' + entry[1] + '</a>';
            }
        }
    }
}
