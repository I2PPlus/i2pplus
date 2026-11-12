/*global env: true */
'use strict';

const doop = require('jsdoc/util/doop');
const fs = require('jsdoc/fs');
const helper = require('jsdoc/util/templateHelper');
const logger = require('jsdoc/util/logger');
const path = require('jsdoc/path');
const taffy = require('@jsdoc/salty').taffy;
const template = require('jsdoc/template');
const util = require('util');

const htmlsafe = helper.htmlsafe;
const linkto = helper.linkto;
const resolveAuthorLinks = helper.resolveAuthorLinks;

const pathToSection = {
    'apps/routerconsole/jsp/js': {name: 'Console', folder: 'console'},
    'apps/i2psnark/res/js': {name: 'I2PSnark', folder: 'i2psnark'},
    'apps/i2ptunnel/jsp/js': {name: 'I2PTunnel', folder: 'i2ptunnel'},
    'apps/imagegen': {name: 'ImageGen', folder: 'imagegen'},
    'apps/susidns/src/js': {name: 'SusiDNS', folder: 'susidns'},
    'apps/susimail/src/js': {name: 'SusiMail', folder: 'susimail'}
};

function getSectionInfo(filePath) {
    if (!filePath) return null;
    for (const prefix in pathToSection) {
        if (filePath.indexOf(prefix) !== -1) {
            return pathToSection[prefix];
        }
    }
    return null;
}

function getSectionFolder(doc) {
    const filePath = (doc.meta && doc.meta.path) || doc.name || '';
    let info = getSectionInfo(filePath);
    if (!info && doc.meta && doc.meta.filename) {
        info = getSectionInfo(doc.meta.filename);
    }
    return info ? info.folder : null;
}

function getSectionName(doc) {
    const filePath = (doc.meta && doc.meta.path) || doc.name || '';
    let info = getSectionInfo(filePath);
    if (!info && doc.meta && doc.meta.filename) {
        info = getSectionInfo(doc.meta.filename);
    }
    return info ? info.name : null;
}

function getFolderFromSectionName(sectionName) {
    for (const k in pathToSection) {
        if (pathToSection[k].name === sectionName) {
            return pathToSection[k].folder;
        }
    }
    return null;
}
const scopeToPunc = helper.scopeToPunc;
const hasOwnProp = Object.prototype.hasOwnProperty;

let data;
let view;

let outdir = path.normalize(env.opts.destination);

function copyFile(source, target, cb) {
  let cbCalled = false;

  const rd = fs.createReadStream(source);
  rd.on("error", function(err) {
    done(err);
  });
  const wr = fs.createWriteStream(target);
  wr.on("error", function(err) {
    done(err);
  });
  wr.on("close", function(ex) {
    done();
  });
  rd.pipe(wr);

  function done(err) {
    if (!cbCalled) {
      cb(err);
      cbCalled = true;
    }
  }
}

function find(spec) {
    return helper.find(data, spec);
}

function tutoriallink(tutorial) {
    return helper.toTutorial(tutorial, null, { tag: 'em', classname: 'disabled', prefix: 'Tutorial: ' });
}

function getAncestorLinks(doclet) {
    return helper.getAncestorLinks(data, doclet);
}

function hashToLink(doclet, hash) {
    if ( !/^(#.+)/.test(hash) ) { return hash; }

    let url = helper.createLink(doclet);

    url = url.replace(/(#.+|$)/, hash);
    return '<a href="' + url + '">' + hash + '</a>';
}

function needsSignature(doclet) {
    let needsSig = false;

    // function and class definitions always get a signature
    if (doclet.kind === 'function' || doclet.kind === 'class' && !doclet.hideconstructor) {
        needsSig = true;
    }
    // typedefs that contain functions get a signature, too
    else if (doclet.kind === 'typedef' && doclet.type && doclet.type.names &&
        doclet.type.names.length) {
        for (let i = 0, l = doclet.type.names.length; i < l; i++) {
            if (doclet.type.names[i].toLowerCase() === 'function') {
                needsSig = true;
                break;
            }
        }
    }
    // and namespaces that are functions get a signature (but finding them is a
    // bit messy)
    else if (doclet.kind === 'namespace' && doclet.meta && doclet.meta.code &&
        doclet.meta.code.type && doclet.meta.code.type.match(/[Ff]unction/)) {
        needsSig = true;
    }

    return needsSig;
}

function getSignatureAttributes(item) {
    const attributes = [];

    if (item.optional) {
        attributes.push('opt');
    }

    if (item.nullable === true) {
        attributes.push('nullable');
    }
    else if (item.nullable === false) {
        attributes.push('non-null');
    }

    return attributes;
}

function updateItemName(item) {
    const attributes = getSignatureAttributes(item);
    let itemName = item.name || '';

    if (item.variable) {
        itemName = '&hellip;' + itemName;
    }

    if (attributes && attributes.length) {
        itemName = util.format( '%s<span class="signature-attributes">%s</span>', itemName,
            attributes.join(', ') );
    }

    return itemName;
}

function addParamAttributes(params) {
    return params.filter(function(param) {
        return param.name && param.name.indexOf('.') === -1;
    }).map(updateItemName);
}

function buildItemTypeStrings(item) {
    const types = [];

    if (item && item.type && item.type.names) {
        item.type.names.forEach(function(name) {
            types.push( linkto(name, htmlsafe(name)) );
        });
    }

    return types;
}

function buildAttribsString(attribs) {
    let attribsString = '';

    if (attribs && attribs.length) {
        attribsString = htmlsafe( util.format('(%s) ', attribs.join(', ')) );
    }

    return attribsString;
}

function addNonParamAttributes(items) {
    let types = [];

    items.forEach(function(item) {
        types = types.concat( buildItemTypeStrings(item) );
    });

    return types;
}

function addSignatureParams(f) {
    const params = f.params ? addParamAttributes(f.params) : [];
    f.signature = util.format( '%s(%s)', (f.signature || ''), params.join(', ') );
}

function addSignatureReturns(f) {
    const attribs = [];
    let attribsString = '';
    let returnTypes = [];
    let returnTypesString = '';
    const source = f.yields || f.returns;

    // jam all the return-type attributes into an array. this could create odd results (for example,
    // if there are both nullable and non-nullable return types), but let's assume that most people
    // who use multiple @return tags aren't using Closure Compiler type annotations, and vice-versa.
    if (source) {
        source.forEach(function(item) {
            helper.getAttribs(item).forEach(function(attrib) {
                if (attribs.indexOf(attrib) === -1) {
                    attribs.push(attrib);
                }
            });
        });

        attribsString = buildAttribsString(attribs);
    }

    if (source) {
        returnTypes = addNonParamAttributes(source);
    }
    if (returnTypes.length) {
        returnTypesString = util.format( ' &rarr; %s{%s}', attribsString, returnTypes.join('|') );
    }

    f.signature = '<span class="signature">' + (f.signature || '') + '</span>' +
        '<span class="type-signature">' + returnTypesString + '</span>';
}

function addSignatureTypes(f) {
    const types = f.type ? buildItemTypeStrings(f) : [];

    f.signature = (f.signature || '') + '<span class="type-signature">' +
        (types.length ? ' :' + types.join('|') : '') + '</span>';
}

function addAttribs(f) {
    const attribs = helper.getAttribs(f);
    const attribsString = buildAttribsString(attribs);
    if (attribsString && attribsString.length) {
        f.attribs = util.format('<span class="type-signature type-signature-' + attribsString.replace(/\(/g, '').replace(/\)/g, '').trim() +'">%s</span>', attribsString);
    }
    else {
        f.attribs = util.format('<span class="type-signature">%s</span>', attribsString);
    }
}

function shortenPaths(files, commonPrefix) {
    Object.keys(files).forEach(function(file) {
        files[file].shortened = files[file].resolved.replace(commonPrefix, '')
            // always use forward slashes
            .replace(/\\/g, '/');
    });

    return files;
}

function getPathFromDoclet(doclet) {
    if (!doclet.meta) {
        return null;
    }

    return doclet.meta.path && doclet.meta.path !== 'null' ?
        path.join(doclet.meta.path, doclet.meta.filename) :
        doclet.meta.filename;
}

function generate(type, title, docs, filename, resolveLinks) {
    resolveLinks = resolveLinks === false ? false : true;

    const docData = {
        type: type,
        title: title,
        docs: docs
    };

    let outpath = path.join(outdir, filename),
        html = view.render('container.tmpl', docData);

    if (resolveLinks) {
        html = helper.resolveLinks(html); // turn {@link foo} into <a href="foodoc.html">foo</a>
    }

    fs.writeFileSync(outpath, html, 'utf8');
}

function generateSourceFiles(sourceFiles, encoding) {
    encoding = encoding || 'utf8';

    const sectionFolders = {};
    for (const k in pathToSection) {
        sectionFolders[pathToSection[k].folder] = true;
    }

    Object.keys(sectionFolders).forEach(function(folder) {
        fs.mkPath(path.join(outdir, folder));
    });

    Object.keys(sourceFiles).forEach(function(file) {
        let source;
        const section = getSectionInfo(sourceFiles[file].resolved);
        const folder = section ? section.folder : '';
        const scriptName = sourceFiles[file].shortened.split('/').pop().replace(/\.js$/, '');
        const sourceOutfile = folder ? folder + '/' + scriptName + '.html' : scriptName + '.html';

        helper.registerLink(sourceFiles[file].shortened, sourceOutfile);

        try {
            source = {
                kind: 'source',
                code: helper.htmlsafe( fs.readFileSync(sourceFiles[file].resolved, encoding) )
            };
        }
        catch(e) {
            logger.error('Error while generating source file %s: %s', file, e.message);
        }

        view.basePath = folder ? '../' : '';
        view.sectionId = folder || '';
        const origNav = view.nav;
        if (folder) {
            view.nav = origNav.replace(/href="([^"#])/g, 'href="../$1');
        }
        generate('Source', sourceFiles[file].shortened, [source], sourceOutfile, false);
        view.nav = origNav;
    });
    view.basePath = '';
    view.sectionId = '';
}

/**
 * Look for classes or functions with the same name as modules (which indicates that the module
 * exports only that class or function), then attach the classes or functions to the `module`
 * property of the appropriate module doclets. The name of each class or function is also updated
 * for display purposes. This function mutates the original arrays.
 *
 * @private
 * @param {Array.<module:jsdoc/doclet.Doclet>} doclets - The array of classes and functions to
 * check.
 * @param {Array.<module:jsdoc/doclet.Doclet>} modules - The array of module doclets to search.
 */
function attachModuleSymbols(doclets, modules) {
    const symbols = {};

    // build a lookup table
    doclets.forEach(function(symbol) {
        symbols[symbol.longname] = symbols[symbol.longname] || [];
        symbols[symbol.longname].push(symbol);
    });

    return modules.map(function(module) {
        if (symbols[module.longname]) {
            module.modules = symbols[module.longname]
                // Only show symbols that have a description. Make an exception for classes, because
                // we want to show the constructor-signature heading no matter what.
                .filter(function(symbol) {
                    return symbol.description || symbol.kind === 'class';
                })
                .map(function(symbol) {
                    symbol = doop(symbol);

                    if (symbol.kind === 'class' || symbol.kind === 'function' && !symbol.hideconstructor) {
                        symbol.name = symbol.name.replace('module:', '(require("') + '"))';
                    }

                    return symbol;
                });
        }
    });
}

function buildCustomNav() {
    let nav = '<h2><a href="index.html">I2P+ JSDoc Index</a></h2>';

    const sectionNames = {};
    for (const k in pathToSection) {
        sectionNames[pathToSection[k].name] = pathToSection[k].folder;
    }

    const sections = {};
    for (const k in pathToSection) sections[pathToSection[k].name] = [];

    const allFiles = find({kind: 'file'}).concat(find({kind: 'module'}));
    const seen = {};
    allFiles.forEach(function(doc) {
        const sectionName = getSectionName(doc);
        if (!sectionName) return;
        const scriptName = (doc.name || '').split('/').pop().replace(/\.js$/, '');
        if (seen[scriptName]) return;
        seen[scriptName] = true;
        sections[sectionName].push(doc);
    });

    nav += '<ul>';
    Object.keys(sections).sort().forEach(function(sectionName) {
        const items = sections[sectionName];
        if (items && items.length) {
            const folder = sectionNames[sectionName];
            items.sort(function(a, b) { return (a.name || '').localeCompare(b.name || ''); });
            nav += '<li class="section-heading ' + folder + '"><h4><a href="' + folder + '/index.html">' + sectionName + '</a></h4></li>';
            items.forEach(function(doc) {
                const scriptName = (doc.name || '').split('/').pop().replace(/\.js$/, '');
                nav += '<li class="' + folder + '"><a href="' + folder + '/' + scriptName + '.html">' + scriptName + '</a></li>';
            });
        }
    });
    nav += '</ul>';

    return nav;
}

function buildNav(members) {
    return buildCustomNav();
}

/**
    @param {TAFFY} taffyData See <http://taffydb.com/>.
    @param {object} opts
    @param {Tutorial} tutorials
 */
exports.publish = function(taffyData, opts, tutorials) {
    const docdash = env && env.conf && env.conf.docdash || {};
    data = taffyData;

    const conf = env.conf.templates || {};
    conf.default = conf.default || {};

    const templatePath = path.normalize(opts.template);
    view = new template.Template( path.join(templatePath, 'tmpl') );

    // claim some special filenames in advance, so the All-Powerful Overseer of Filename Uniqueness
    // doesn't try to hand them out later
    const indexUrl = helper.getUniqueFilename('index');
    // don't call registerLink() on this one! 'index' is also a valid longname

    const globalUrl = helper.getUniqueFilename('global');
    helper.registerLink('global', globalUrl);

    // set up templating
    view.layout = conf.default.layoutFile ?
        path.getResourcePath(path.dirname(conf.default.layoutFile),
            path.basename(conf.default.layoutFile) ) :
        'layout.tmpl';

    // set up tutorials for helper
    helper.setTutorials(tutorials);

    data = helper.prune(data);

    docdash.sort !== false && data.sort('longname, version, since');
    helper.addEventListeners(data);

    let sourceFiles = {};
    const sourceFilePaths = [];
    data().each(function(doclet) {
         if(docdash.removeQuotes){
            if(docdash.removeQuotes === "all"){
                if(doclet.name){
                    doclet.name = doclet.name.replace(/"/g, '');
                    doclet.name = doclet.name.replace(/'/g, '');
                }
                if(doclet.longname){
                    doclet.longname = doclet.longname.replace(/"/g, '');
                    doclet.longname = doclet.longname.replace(/'/g, '');
                }
            }
            else if(docdash.removeQuotes === "trim"){
                if(doclet.name){
                    doclet.name = doclet.name.replace(/^"(.*)"$/, '$1');
                    doclet.name = doclet.name.replace(/^'(.*)'$/, '$1');
                }
                if(doclet.longname){
                    doclet.longname = doclet.longname.replace(/^"(.*)"$/, '$1');
                    doclet.longname = doclet.longname.replace(/^'(.*)'$/, '$1');
                }
            }
         }
         doclet.attribs = '';

        if (doclet.examples) {
            doclet.examples = doclet.examples.map(function(example) {
                let caption, code;

                if (example && example.match(/^\s*<caption>([\s\S]+?)<\/caption>(\s*[\n\r])([\s\S]+)$/i)) {
                    caption = RegExp.$1;
                    code = RegExp.$3;
                }

                return {
                    caption: caption || '',
                    code: code || example || ''
                };
            });
        }
        if (doclet.see) {
            doclet.see.forEach(function(seeItem, i) {
                doclet.see[i] = hashToLink(doclet, seeItem);
            });
        }

        // build a list of source files
        let sourcePath;
        if (doclet.meta) {
            sourcePath = getPathFromDoclet(doclet);
            sourceFiles[sourcePath] = {
                resolved: sourcePath,
                shortened: null
            };
            if (sourceFilePaths.indexOf(sourcePath) === -1) {
                sourceFilePaths.push(sourcePath);
            }
        }
    });

    // update outdir if necessary, then create outdir
    const packageInfo = ( find({kind: 'package'}) || [] ) [0];
    if (packageInfo) {
        const subdirs = [outdir];

        if (packageInfo.name) {
            const packageName = packageInfo.name.split('/');

            if (packageName.length > 1 && docdash.scopeInOutputPath !== false) {
                subdirs.push(packageName[0]);
            }

            if (docdash.nameInOutputPath !== false) {
                subdirs.push((packageName.length > 1 ? packageName[1] : packageName[0]));
            }
        }

        if (packageInfo.version && docdash.versionInOutputPath !== false) {
            subdirs.push(packageInfo.version);
        }

        if (subdirs.length > 1) {
            outdir = path.join.apply(null, subdirs);
        }
    }

    fs.mkPath(outdir);

    // copy the template's static files to outdir
    const fromDir = path.join(templatePath, 'static');
    const staticFiles = fs.ls(fromDir, 3);

    staticFiles.forEach(function(fileName) {
        const toDir = fs.toDir( fileName.replace(fromDir, outdir) );
        fs.mkPath(toDir);
        copyFile(fileName, path.join(toDir, path.basename(fileName)), function(err){if(err) console.err(err);});
    });

    // copy user-specified static files to outdir
    let staticFilePaths;
    let staticFileFilter;
    let staticFileScanner;
    if (conf.default.staticFiles) {
        // The canonical property name is `include`. We accept `paths` for backwards compatibility
        // with a bug in JSDoc 3.2.x.
        staticFilePaths = conf.default.staticFiles.include ||
            conf.default.staticFiles.paths ||
            [];
        staticFileFilter = new (require('jsdoc/src/filter')).Filter(conf.default.staticFiles);
        staticFileScanner = new (require('jsdoc/src/scanner')).Scanner();

        staticFilePaths.forEach(function(filePath) {
            const extraStaticFiles = staticFileScanner.scan([filePath], 10, staticFileFilter);

            extraStaticFiles.forEach(function(fileName) {
                const sourcePath = fs.toDir(filePath);
                const toDir = fs.toDir( fileName.replace(sourcePath, outdir) );
                fs.mkPath(toDir);
                copyFile(fileName, path.join(toDir, path.basename(fileName)), function(err){if(err) console.err(err);});
            });
        });
    }

    if (sourceFilePaths.length) {
        sourceFiles = shortenPaths( sourceFiles, path.commonPrefix(sourceFilePaths) );
    }
    data().each(function(doclet) {
        let url = helper.createLink(doclet);
        if (docdash.noURLEncode) {
            url = decodeURI(url);
        }
        helper.registerLink(doclet.longname, url);

        // add a shortened version of the full path
        let docletPath;
        if (doclet.meta) {
            docletPath = getPathFromDoclet(doclet);
            docletPath = sourceFiles[docletPath].shortened;
            if (docletPath) {
                doclet.meta.shortpath = docletPath;
            }
        }
    });

    data().each(function(doclet) {
        const url = helper.longnameToUrl[doclet.longname];

        if (url.indexOf('#') > -1) {
            doclet.id = helper.longnameToUrl[doclet.longname].split(/#/).pop();
        }
        else {
            doclet.id = doclet.name;
        }

        if ( needsSignature(doclet) ) {
            addSignatureParams(doclet);
            addSignatureReturns(doclet);
            addAttribs(doclet);
        }
    });

    // do this after the urls have all been generated
    data().each(function(doclet) {
        doclet.ancestors = getAncestorLinks(doclet);

        if (doclet.kind === 'member') {
            addSignatureTypes(doclet);
            addAttribs(doclet);
        }

        if (doclet.kind === 'constant') {
            addSignatureTypes(doclet);
            addAttribs(doclet);
            doclet.kind = 'member';
        }
    });

    const members = helper.getMembers(data);
    members.tutorials = tutorials.children;

    // output pretty-printed source files by default
    const outputSourceFiles = conf.default && conf.default.outputSourceFiles !== false
        ? true
        : false;

    // add template helpers
    view.find = find;
    view.linkto = linkto;
    view.resolveAuthorLinks = resolveAuthorLinks;
    view.tutoriallink = tutoriallink;
    view.htmlsafe = htmlsafe;
    view.outputSourceFiles = outputSourceFiles;
    view.getSectionFolder = getSectionFolder;

    // add source files to members for nav
    const sourceFilesForNav = find({kind: 'file'});
    if (!members.sourceFiles) members.sourceFiles = [];
    members.sourceFiles = members.sourceFiles.concat(sourceFilesForNav);

    // build nav first so it's available for source file pages
    view.nav = buildNav(members);

    // generate the pretty-printed source files first so other pages can link to them
    if (outputSourceFiles) {
        generateSourceFiles(sourceFiles, opts.encoding);
    }

    attachModuleSymbols( find({ longname: {left: 'module:'} }), members.modules );

    if (members.globals.length) {
        generate('', 'Global', [{kind: 'globalobj'}], globalUrl);
    }

    // index page displays information from package.json and lists files
    const files = find({kind: 'file'}).sort(function(a, b) {
        return (a.section || '').localeCompare(b.section || '') || (a.name || '').localeCompare(b.name || '');
    });
    const packages = find({kind: 'package'});

    view.basePath = '';
    view.sectionIndex = false;
    view.sectionId = '';
    generate('', 'Home',
        packages.concat(
            [{kind: 'mainpage', readme: opts.readme, longname: (opts.mainpagetitle) ? opts.mainpagetitle : 'Main Page'}]
        ).concat(files),
    indexUrl);

    // generate section index pages
    const sectionDocs = {};
    for (const k in pathToSection) {
        const info = pathToSection[k];
        sectionDocs[info.name] = [];
    }
    files.forEach(function(file) {
        const sectionName = file.section || getSectionName(file);
        if (sectionName && sectionDocs[sectionName]) {
            sectionDocs[sectionName].push(file);
        }
    });
    Object.keys(sectionDocs).forEach(function(sectionName) {
        const folder = getFolderFromSectionName(sectionName);
        const docs = sectionDocs[sectionName];
        if (docs.length && folder) {
            view.sectionIndex = true;
            view.basePath = '../';
            view.sectionId = folder;
            const origNav = view.nav;
            view.nav = origNav.replace(/href="([^"#])/g, 'href="../$1');
            generate('', sectionName, docs, folder + '/index.html');
            view.nav = origNav;
            view.sectionIndex = false;
            view.basePath = '';
            view.sectionId = '';
        }
    });

    // common nav generation, no need for templating here, we already have full html
    if (docdash.commonNav) {
        fs.writeFileSync(path.join(outdir, 'nav.inc.html'), view.nav, 'utf8');
    }

    // set up the lists that we'll use to generate pages
    const classes = taffy(members.classes);
    const modules = taffy(members.modules);
    const namespaces = taffy(members.namespaces);
    const mixins = taffy(members.mixins);
    const externals = taffy(members.externals);
    const interfaces = taffy(members.interfaces);

    Object.keys(helper.longnameToUrl).forEach(function(longname) {
        const myModules = helper.find(modules, {longname: longname});
        if (myModules.length) {
            generate('Module', myModules[0].name, myModules, helper.longnameToUrl[longname]);
        }

        const myClasses = helper.find(classes, {longname: longname});
        if (myClasses.length) {
            generate('Class', myClasses[0].name, myClasses, helper.longnameToUrl[longname]);
        }

        const myNamespaces = helper.find(namespaces, {longname: longname});
        if (myNamespaces.length) {
            generate('Namespace', myNamespaces[0].name, myNamespaces, helper.longnameToUrl[longname]);
        }

        const myMixins = helper.find(mixins, {longname: longname});
        if (myMixins.length) {
            generate('Mixin', myMixins[0].name, myMixins, helper.longnameToUrl[longname]);
        }

        const myExternals = helper.find(externals, {longname: longname});
        if (myExternals.length) {
            generate('External', myExternals[0].name, myExternals, helper.longnameToUrl[longname]);
        }

        const myInterfaces = helper.find(interfaces, {longname: longname});
        if (myInterfaces.length) {
            generate('Interface', myInterfaces[0].name, myInterfaces, helper.longnameToUrl[longname]);
        }
    });

    // TODO: move the tutorial functions to templateHelper.js
    function generateTutorial(title, tutorial, filename) {
        const tutorialData = {
            title: title,
            header: tutorial.title,
            content: tutorial.parse(),
            children: tutorial.children
        };

        const tutorialPath = path.join(outdir, filename);
        let html = view.render('tutorial.tmpl', tutorialData);

        // yes, you can use {@link} in tutorials too!
        html = helper.resolveLinks(html); // turn {@link foo} into <a href="foodoc.html">foo</a>
        fs.writeFileSync(tutorialPath, html, 'utf8');
    }

    // tutorials can have only one parent so there is no risk for loops
    function saveChildren(node) {
        node.children.forEach(function(child) {
            generateTutorial('Tutorial: ' + child.title, child, helper.tutorialToUrl(child.name));
            saveChildren(child);
        });
    }

    saveChildren(tutorials);
};
