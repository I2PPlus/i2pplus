'use strict';

var pathToSection = {
    'apps/routerconsole/jsp/js': {name: 'Console', folder: 'console'},
    'apps/i2psnark/res/js': {name: 'I2PSnark', folder: 'i2psnark'},
    'apps/i2ptunnel/jsp/js': {name: 'I2PTunnel', folder: 'i2ptunnel'},
    'apps/imagegen': {name: 'ImageGen', folder: 'imagegen'},
    'apps/susidns/src/js': {name: 'SusiDNS', folder: 'susidns'},
    'apps/susimail/src/js': {name: 'SusiMail', folder: 'susimail'}
};

function getSectionInfo(filePath) {
    if (!filePath) return null;
    for (var prefix in pathToSection) {
        if (filePath.indexOf(prefix) !== -1) {
            return pathToSection[prefix];
        }
    }
    return null;
}

var fileSections = {};

exports.handlers = {
    newDoclet: function(e) {
        var doclet = e.doclet;

        if (doclet.kind === 'file') {
            var filePath = doclet.name || (doclet.meta && doclet.meta.filename);
            var info = getSectionInfo(filePath);
            if (info) {
                doclet.section = info.name;
                fileSections[doclet.longname] = info.name;
            }
        }

        if (!doclet.section && doclet.memberof && fileSections[doclet.memberof]) {
            doclet.section = fileSections[doclet.memberof];
        }

        if (!doclet.section && doclet.meta) {
            var path = doclet.meta.path || doclet.meta.filename || doclet.name;
            var info = getSectionInfo(path);
            if (info) {
                doclet.section = info.name;
            }
        }
    }
};
