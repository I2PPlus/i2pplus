/**
 * @module docdash-collapse
 * @description Keeps the generated nav short: every submenu starts hidden, a collapsed
 * section header toggles its own list, and only the branch holding the page just loaded
 * is opened. Re-runs after a search, which leaves every item visible.
 * @see {@link https://github.com/clenemt/docdash}
 * @license Apache-2.0 (upstream docdash)
 */
function hideAllButCurrent(){
    //by default all submenut items are hidden
    //but we need to rehide them for search
    document.querySelectorAll("nav > ul").forEach(function(parent) {
        if (parent.className.indexOf("collapse_top") !== -1) {
            parent.style.display = "none";
        }
    });
    document.querySelectorAll("nav > ul > li > ul li").forEach(function(parent) {
        parent.style.display = "none";
    });
    document.querySelectorAll("nav > h3").forEach(function(section) {
        if (section.className.indexOf("collapsed_header") !== -1) {
            section.addEventListener("click", function(){
                if (section.nextSibling.style.display === "none") {
                    section.nextSibling.style.display = "block";
                } else {
                    section.nextSibling.style.display = "none";
                }
            });
        }
    });

    //only current page (if it exists) should be opened
    const file = window.location.pathname.split("/").pop().replace(/\.html/, '');
    document.querySelectorAll("nav > ul > li > a").forEach(function(parent) {
        const href = parent.attributes.href.value.replace(/\.html/, '');
        if (file === href) {
            if (parent.parentNode.parentNode.className.indexOf("collapse_top") !== -1) {
                parent.parentNode.parentNode.style.display = "block";
            }
            parent.parentNode.querySelectorAll("ul li").forEach(function(elem) {
                elem.style.display = "block";
            });
        }
    });
}

hideAllButCurrent();
