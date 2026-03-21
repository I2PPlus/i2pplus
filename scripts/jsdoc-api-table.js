#!/usr/bin/env node
'use strict';

/**
 * Generate an API summary table from JSDoc JSON output.
 * Reads stdin (piped from `jsdoc -X`) and writes api-table.html to dist/jsdoc/.
 */

const pathToSection = {
  'apps/routerconsole/jsp/js': {name: 'Console', folder: 'console'},
  'apps/i2psnark/res/js': {name: 'I2PSnark', folder: 'i2psnark'},
  'apps/i2ptunnel/jsp/js': {name: 'I2PTunnel', folder: 'i2ptunnel'},
  'apps/imagegen': {name: 'ImageGen', folder: 'imagegen'},
  'apps/susidns/src/js': {name: 'SusiDNS', folder: 'susidns'},
  'apps/susimail/src/js': {name: 'SusiMail', folder: 'susimail'}
};

function getSection(filePath) {
  if (!filePath) return null;
  for (const prefix in pathToSection) {
    if (filePath.indexOf(prefix) !== -1) {
      return pathToSection[prefix].name;
    }
  }
  return null;
}

function getSectionFolder(sectionName) {
  for (const prefix in pathToSection) {
    if (pathToSection[prefix].name === sectionName) {
      return pathToSection[prefix].folder;
    }
  }
  return null;
}

function getFileName(doclet) {
  if (doclet.meta && doclet.meta.filename) {
    return doclet.meta.filename.replace(/\.js$/, '');
  }
  return '';
}

function buildSignature(params) {
  if (!params || !params.length) return '()';
  const names = params.map(p => {
    let name = p.name || '';
    if (p.variable) name = '...' + name;
    if (p.optional) name = '[' + name + ']';
    return name;
  });
  return '(' + names.join(', ') + ')';
}

function buildParamTypes(params) {
  if (!params || !params.length) return '';
  return params.map(p => {
    const types = (p.type && p.type.names) ? p.type.names.join('|') : '*';
    let label = p.name || '';
    if (p.optional) label = '[' + label + ']';
    return label + ': ' + types;
  }).join(', ');
}

function buildReturnTypes(returns) {
  if (!returns || !returns.length) return '';
  return returns.map(r => {
    if (!r.type || !r.type.names) return '*';
    return r.type.names.map(n => {
      // Simplify: Promise.<void> -> Promise<void>
      return n.replace(/\.</g, '<').replace(/>?$/, '>');
    }).join('|');
  }).join('|');
}

function escapeHtml(str) {
  if (!str) return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function stripHtml(str) {
  if (!str) return '';
  return str.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
}

function truncate(str, maxLen) {
  if (!str) return '';
  const oneLine = stripHtml(str);
  if (oneLine.length <= maxLen) return oneLine;
  return oneLine.substring(0, maxLen - 3) + '...';
}

function getFilePath(doclet) {
  if (!doclet.meta) return '';
  const dir = doclet.meta.path || '';
  const file = doclet.meta.filename || '';
  return path.join(dir, file);
}

function processDoclets(doclets) {
  const functions = [];
  const fileSections = {};

  for (const d of doclets) {
    if (d.kind === 'file') {
      const filePath = d.name || (d.meta && d.meta.filename) || '';
      const section = getSection(filePath);
      if (section) {
        fileSections[d.longname] = section;
      }
    }
  }

  for (const d of doclets) {
    if (d.kind !== 'function' && d.kind !== 'class') continue;
    if (d.access === 'private') continue;
    if (d.undocumented) continue;
    if (!d.name) continue;

    let section = null;

    if (d.memberof && fileSections[d.memberof]) {
      section = fileSections[d.memberof];
    }
    if (!section && d.meta) {
      const p = d.meta.path || '';
      section = getSection(p);
    }
    if (!section) continue;

    const fileName = getFileName(d);
    const folder = getSectionFolder(section);
    const href = folder ? folder + '/' + fileName + '.html' : fileName + '.html';

    functions.push({
      name: d.name,
      section: section,
      fileName: fileName,
      href: href,
      description: truncate(d.summary || d.description, 80),
      signature: buildSignature(d.params),
      paramTypes: buildParamTypes(d.params),
      returnType: buildReturnTypes(d.returns),
      kind: d.kind,
      async: d.async || false,
      isExported: (d.scope === 'global' || d.scope === 'static') && !d.memberof
    });
  }

  return functions;
}

function generateHtml(functions) {
  const sections = {};
  for (const f of functions) {
    if (!sections[f.section]) sections[f.section] = [];
    sections[f.section].push(f);
  }

  for (const sec in sections) {
    sections[sec].sort((a, b) => a.name.localeCompare(b.name));
  }

  const sectionNames = Object.keys(sections).sort();

  let rows = '';
  for (const f of functions) {
    const returnType = f.returnType ? ' &rarr; ' + escapeHtml(f.returnType) : '';
    const asyncBadge = f.async ? ' <span class="badge-async">async</span>' : '';
    const classBadge = f.kind === 'class' ? ' <span class="badge-class">class</span>' : '';
    const signature = escapeHtml(f.signature);
    const description = escapeHtml(f.description);
    const params = f.paramTypes ? escapeHtml(f.paramTypes) : '';
    const paramTitle = params ? ' title="' + params + '"' : '';

    rows += '      <tr data-section="' + escapeHtml(f.section) + '">\n';
    rows += '        <td class="col-section">' + escapeHtml(f.section) + '</td>\n';
    rows += '        <td class="col-name"><a href="' + escapeHtml(f.href) + '">' + escapeHtml(f.name) + '</a>' + asyncBadge + classBadge + '</td>\n';
    rows += '        <td class="col-sig"><code>' + signature + '</code></td>\n';
    rows += '        <td class="col-return"><code>' + returnType + '</code></td>\n';
    rows += '        <td class="col-desc"' + paramTitle + '>' + description + '</td>\n';
    rows += '      </tr>\n';
  }

  const filterButtons = sectionNames.map(s =>
    '      <button class="filter-btn" data-filter="' + escapeHtml(s) + '">' + escapeHtml(s) + '</button>'
  ).join('\n');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>API Summary - I2P+ JavaScript</title>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link type="text/css" rel="stylesheet" href="styles/jsdoc.css">
  <link type="text/css" rel="stylesheet" href="styles/fonts.css">
  <link type="text/css" rel="stylesheet" href="styles/jsdoc-dark.css">
</head>
<body>
<div class="api-page">
  <h1>API Summary</h1>
  <p class="subtitle">${functions.length} documented functions across ${sectionNames.length} subsystems &middot; <a href="index.html">Back to docs</a></p>

  <div class="filters">
    <button class="filter-btn active" data-filter="all">All</button>
${filterButtons}
  </div>

  <table class="api-table">
    <thead>
      <tr>
        <th class="col-section">Section</th>
        <th class="col-name">Function</th>
        <th class="col-sig">Signature</th>
        <th class="col-return">Returns</th>
        <th class="col-desc">Description</th>
      </tr>
    </thead>
    <tbody>
${rows}
    </tbody>
  </table>
</div>
<script>
(function() {
  var btns = document.querySelectorAll('.filter-btn');
  var rows = document.querySelectorAll('.api-table tbody tr');
  btns.forEach(function(btn) {
    btn.addEventListener('click', function() {
      btns.forEach(function(b) { b.classList.remove('active'); });
      btn.classList.add('active');
      var filter = btn.getAttribute('data-filter');
      rows.forEach(function(row) {
        if (filter === 'all' || row.getAttribute('data-section') === filter) {
          row.style.display = '';
        } else {
          row.style.display = 'none';
        }
      });
    });
  });
})();
</script>
</body>
</html>`;
}

// --- Main ---

const fs = require('fs');
const path = require('path');

let input = '';

process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => { input += chunk; });
process.stdin.on('end', () => {
  let doclets;
  try {
    doclets = JSON.parse(input);
  } catch (e) {
    process.stderr.write('Error: Failed to parse JSDoc JSON input\n');
    process.exit(1);
  }

  const functions = processDoclets(doclets);
  if (!functions.length) {
    process.stderr.write('No functions found in JSDoc output\n');
    process.exit(0);
  }

  const html = generateHtml(functions);

  const outDir = process.argv[2] || 'dist/jsdoc';
  const outFile = path.join(outDir, 'api-table.html');

  try {
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(outFile, html, 'utf8');
    process.stdout.write('API table written to ' + outFile + ' (' + functions.length + ' entries)\n');
  } catch (e) {
    process.stderr.write('Error writing ' + outFile + ': ' + e.message + '\n');
    process.exit(1);
  }
});
