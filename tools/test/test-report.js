/**
 * Interactive controls for the JUnit HTML report: filter test classes by name,
 * sort a module table by column, and expand a class row to its per-method list.
 * unit-tests-to-html.py inlines this file into one <script> in the report's
 * head, so the page stays a single self-contained file.
 *
 * The source is written to survive that inlining, which strips every "//"
 * comment and collapses each run of whitespace to a single space: no statement
 * may depend on a line break, no string or regular expression may contain a
 * "//" pair, and no template literal may span lines. The harness pins that in
 * tools/test/js/dom/testReport.test.js.
 *
 * @license AGPL3 or later
 */

// The report's own markup is the contract here. Each module section holds one
// <table> per java package, and every one of those tables repeats the module id
// in data-sort-table, so a sort has to be scoped to the table the header sits
// in, never to the attribute value. Each test class is a <tr class="test-row">
// followed by a hidden <tr class="detail-row"> holding that class's methods;
// the two are one unit and must move together.
(function () {
  "use strict";

  // ---- pure decision helpers, pinned by the harness without a browser ----

  // A cell's sort key: a number for the count and duration columns, the text
  // for everything else, and null for a blank, which sorts last either way.
  function cellKey(text) {
    const s = String(text === null || text === undefined ? "" : text).trim();
    if (s === "") {return null;}
    const n = Number(s);
    if (Number.isFinite(n)) {return n;}
    // The generator writes durations as "0.123s", which Number() rejects.
    const dur = /^([0-9]*\.?[0-9]+)s$/.exec(s);
    return dur ? Number(dur[1]) : s;
  }

  // Blank cells sort last in both directions, so an empty column cannot trade
  // places with a real value just because the sort was reversed.
  function compareCells(a, b, dir) {
    const ka = cellKey(a);
    const kb = cellKey(b);
    if (ka === null || kb === null) {
      if (ka === null && kb === null) {return 0;}
      return ka === null ? 1 : -1;
    }
    // Both kinds of comparison are reversed by the direction, or a desc sort
    // would leave the numeric columns ascending.
    const r = typeof ka === "number" && typeof kb === "number" ? ka - kb : String(ka).localeCompare(String(kb));
    return dir === "desc" ? -r : r;
  }

  // One test class plus the detail rows that belong to it. cells holds the
  // class row's cell text, which is what a sort compares, so the ordering is
  // decided without touching the document.
  function sortGroups(groups, col, dir) {
    return groups.slice().sort((g1, g2) => compareCells(g1.cells[col], g2.cells[col], dir));
  }

  // Every whitespace-separated term has to appear somewhere in the class name
  // or in its method list, so "ProfileOrganizer decision" narrows rather than
  // finds nothing, and a method name alone still reaches its class.
  function matchesQuery(needles, query) {
    const terms = String(query === null || query === undefined ? "" : query).trim().toLowerCase().split(/\s+/);
    if (terms.length === 0 || terms[0] === "") {return true;}
    const hay = needles.join(" ").toLowerCase();
    return terms.every((t) => hay.indexOf(t) !== -1);
  }

  // The next direction for a header already sorted in dir: a second click
  // reverses, a third returns to the report's own alphabetical order.
  function nextDir(current) {
    if (current === "asc") {return "desc";}
    return current === "desc" ? "" : "asc";
  }

  // ---- document wiring ----

  function init(doc) {
    const tables = Array.prototype.slice.call(doc.querySelectorAll("table"));

    // A group is the class row and its detail rows, kept together so sorting
    // cannot detach a case list from the class it belongs to.
    // querySelector rather than the table DOM collections, and td rather than
    // row.cells: the same elements either way, and the selector form is what
    // the harness's DOM implements too.
    function groupsOf(tbody) {
      const groups = [];
      let current = null;
      Array.prototype.forEach.call(tbody.children, (row) => {
        if (row.classList.contains("detail-row") && current) {
          current.rows.push(row);
          return;
        }
        current = {rows: [row], cells: cellsOf(row)};
        groups.push(current);
      });
      return groups;
    }

    function cellsOf(row) {
      return Array.prototype.map.call(row.querySelectorAll("td"), (c) => c.textContent);
    }

    // The class name plus every method name in its detail row, which is what a
    // filter query is matched against.
    function needlesOf(group) {
      const out = [group.cells[0] || ""];
      group.rows.forEach((row) => {
        if (row.classList.contains("detail-row")) {out.push(row.textContent);}
      });
      return out;
    }

    function show(row, on) {
      row.style.display = on ? "" : "none";
    }

    // A detail row's own expanded state is remembered on the row, so clearing a
    // filter restores what the reader had opened instead of collapsing it.
    function detailOpen(row) {
      return row.getAttribute("data-open") === "1";
    }

    function setDetail(row, open) {
      row.setAttribute("data-open", open ? "1" : "0");
      row.style.display = open ? "" : "none";
    }

    // ---- sorting ----

    // The generator writes each table alphabetically by class name, and that is
    // the order a third click on a header returns to. It has to be captured
    // before the first sort, not when the restore is asked for.
    function rememberOriginal(tbody) {
      if (tbody.__original) {return;}
      tbody.__original = Array.prototype.slice.call(tbody.children);
    }

    function restoreOriginal(tbody) {
      (tbody.__original || []).forEach((row) => {tbody.appendChild(row);});
    }

    function sortByHeader(th) {
      const table = th.closest("table");
      if (!table) {return;}
      const tbody = table.querySelector("tbody");
      if (!tbody) {return;}
      rememberOriginal(tbody);
      const dir = nextDir(th.getAttribute("data-dir") || "");
      Array.prototype.forEach.call(table.querySelectorAll("th.sortable"), (other) => {
        other.removeAttribute("data-dir");
        other.classList.remove("sort-asc");
        other.classList.remove("sort-desc");
      });
      if (dir) {
        th.setAttribute("data-dir", dir);
        th.classList.add(dir === "asc" ? "sort-asc" : "sort-desc");
      }
      const col = Number(th.getAttribute("data-sort-col"));
      // With no direction the table goes back to the order the generator wrote.
      if (!dir) {
        restoreOriginal(tbody);
        return;
      }
      sortGroups(groupsOf(tbody), col, dir).forEach((group) => {
        group.rows.forEach((row) => {tbody.appendChild(row);});
      });
    }

    // ---- filtering ----

    // The <details> wrappers start closed, so a match inside one is invisible
    // until the filter opens it. What the reader had opened is restored when
    // the filter is cleared, which is what data-filter-open records.
    function applyFilter(query) {
      let matches = 0;
      tables.forEach((table) => {
        const tbody = table.querySelector("tbody");
        if (!tbody) {return;}
        groupsOf(tbody).forEach((group) => {
          const hit = matchesQuery(needlesOf(group), query);
          if (hit) {matches += 1;}
          show(group.rows[0], hit);
          group.rows.slice(1).forEach((detail) => {
            show(detail, hit && detailOpen(detail));
          });
        });
      });
      // The open state is carried by the content attribute rather than the
      // .open property, so the same code works in the harness's DOM.
      Array.prototype.forEach.call(doc.querySelectorAll("details"), (box) => {
        const hasMatch = Array.prototype.some.call(box.querySelectorAll("tr.test-row"), (row) => row.style.display !== "none");
        if (query === "") {
          if (box.getAttribute("data-filter-open") === "1") {
            box.removeAttribute("data-filter-open");
            box.removeAttribute("open");
          }
          return;
        }
        if (hasMatch && !box.hasAttribute("open")) {
          box.setAttribute("data-filter-open", "1");
          box.setAttribute("open", "");
        }
      });
      const bar = doc.querySelector(".search-input");
      if (bar) {
        if (query === "") {bar.classList.remove("active");} else {bar.classList.add("active");}
        bar.setAttribute("title", query === "" ? "" : matches + " of the test classes match");
      }
    }

    // ---- wiring ----

    doc.addEventListener("click", (ev) => {
      const target = ev.target;
      if (!target || !target.closest) {return;}
      const th = target.closest("th.sortable");
      if (th) {sortByHeader(th); return;}
      const row = target.closest("tr[data-toggle]");
      if (row) {
        const detail = doc.getElementById(row.getAttribute("data-toggle"));
        if (detail) {setDetail(detail, !detailOpen(detail));}
      }
    });

    const bar = doc.querySelector(".search-input");
    if (bar) {
      bar.addEventListener("input", () => {applyFilter(bar.value.trim());});
      // The field shows a clear mark once a filter is on, and clicking it is
      // how the reader gets the whole table back.
      bar.addEventListener("click", () => {
        if (bar.classList.contains("active")) {
          bar.value = "";
          applyFilter("");
        }
      });
    }
  }

  // Exported for the harness, which runs this file in a bare sandbox and calls
  // the helpers directly; the browser sees the same object on window.
  this.TestReport = {cellKey: cellKey, compareCells: compareCells, sortGroups: sortGroups, matchesQuery: matchesQuery, nextDir: nextDir, init: init};

  if (typeof document !== "undefined" && document.querySelectorAll) {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => {init(document);});
    } else {
      init(document);
    }
  }
}).call(typeof self !== "undefined" ? self : globalThis);
