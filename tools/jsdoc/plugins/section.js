/**
 * @module section
 * @file section.js - jsdoc plugin tagging each doclet with its subsystem.
 * @description Maps a source path to the subsystem section it belongs to and
 * stamps that on the file doclet, then inherits it to the members documented in
 * the file, so the generated API pages group by application instead of listing
 * every class in one flat index.
 * @author dr|z3d
 * @license AGPL3 or later
 */
'use strict';

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
    for (const prefix of Object.keys(pathToSection)) {
        if (filePath.indexOf(prefix) !== -1) {
            return pathToSection[prefix];
        }
    }
    return null;
}

const fileSections = {};

exports.handlers = {
    newDoclet: function(e) {
        const doclet = e.doclet;

        if (doclet.kind === 'file') {
            const filePath = doclet.name || (doclet.meta && doclet.meta.filename);
            const info = getSectionInfo(filePath);
            if (info) {
                doclet.section = info.name;
                fileSections[doclet.longname] = info.name;
            }
        }

        if (!doclet.section && doclet.memberof && fileSections[doclet.memberof]) {
            doclet.section = fileSections[doclet.memberof];
        }

        if (!doclet.section && doclet.meta) {
            const path = doclet.meta.path || doclet.meta.filename || doclet.name;
            const info = getSectionInfo(path);
            if (info) {
                doclet.section = info.name;
            }
        }
    }
};
