/* morphdom.js - Fast and lightweight DOM diffing/patching */
/* https://github.com/patrick-steele-idem/morphdom */
/* License: MIT */
'use strict';

var doc = document;
var DOCUMENT_FRAGMENT_NODE = 11;
var ELEMENT_NODE = 1;
var TEXT_NODE = 3;
var COMMENT_NODE = 8;

var NS_XHTML = 'http://www.w3.org/1999/xhtml';

function defaultGetNodeKey(node) {
  return node && ((node.getAttribute && node.getAttribute('id')) || node.id);
}

function compareNodeNames(fromEl, toEl) {
  var fromNodeName = fromEl.nodeName;
  var toNodeName = toEl.nodeName;

  if (fromNodeName === toNodeName) return true;

  var fromCodeStart = fromNodeName.charCodeAt(0);
  var toCodeStart = toNodeName.charCodeAt(0);

  if (fromCodeStart <= 90 && toCodeStart >= 97) {
    return fromNodeName === toNodeName.toUpperCase();
  } else if (toCodeStart <= 90 && fromCodeStart >= 97) {
    return toNodeName === fromNodeName.toUpperCase();
  } else {
    return false;
  }
}

function createElementNS(name, namespaceURI) {
  return !namespaceURI || namespaceURI === NS_XHTML
    ? doc.createElement(name)
    : doc.createElementNS(namespaceURI, name);
}

function moveChildren(fromEl, toEl) {
  while (fromEl.firstChild) {
    toEl.appendChild(fromEl.firstChild);
  }
  return toEl;
}

function syncBooleanAttrProp(fromEl, toEl, name) {
  if (fromEl[name] !== toEl[name]) {
    fromEl[name] = toEl[name];
    fromEl[name] ? fromEl.setAttribute(name, '') : fromEl.removeAttribute(name);
  }
}

var specialElHandlers = {
  TEXTAREA: function(fromEl, toEl) {
    if (fromEl.value !== toEl.value) {
      fromEl.value = toEl.value;
    }
    var firstChild = fromEl.firstChild;
    if (firstChild && firstChild.nodeValue !== toEl.value) {
      firstChild.nodeValue = toEl.value;
    }
  },
  SELECT: function(fromEl, toEl) {
    if (!toEl.hasAttribute('multiple')) {
      var curChild = fromEl.firstChild;
      var i = 0;
      var selected = -1;
      while (curChild) {
        if (curChild.nodeName === 'OPTION' && curChild.hasAttribute('selected')) {
          selected = i;
          break;
        }
        curChild = curChild.nextSibling;
        i++;
      }
      fromEl.selectedIndex = selected;
    }
  },
  INPUT: function(fromEl, toEl) {
    syncBooleanAttrProp(fromEl, toEl, 'checked');
    syncBooleanAttrProp(fromEl, toEl, 'disabled');

    if (fromEl.value !== toEl.value) {
      fromEl.value = toEl.value;
    }

    if (!toEl.hasAttribute('value')) {
      fromEl.removeAttribute('value');
    }
  }
};

function morphAttrs(fromNode, toNode) {
  var toNodeAttrs = toNode.attributes;
  var attr, attrName, attrNamespaceURI, attrValue;

  for (var i = toNodeAttrs.length - 1; i >= 0; i--) {
    attr = toNodeAttrs[i];
    attrName = attr.name;
    attrNamespaceURI = attr.namespaceURI;
    attrValue = attr.value;

    if (attrNamespaceURI) {
      attrName = attr.localName || attrName;
      fromNode.setAttributeNS(attrNamespaceURI, attrName, attrValue);
    } else {
      fromNode.setAttribute(attrName, attrValue);
    }
  }

  var fromNodeAttrs = fromNode.attributes;
  for (var d = fromNodeAttrs.length - 1; d >= 0; d--) {
    attr = fromNodeAttrs[d];
    attrName = attr.name;
    attrNamespaceURI = attr.namespaceURI;

    if (attrNamespaceURI) {
      attrName = attr.localName || attrName;
      if (!toNode.hasAttributeNS(attrNamespaceURI, attrName)) {
        fromNode.removeAttributeNS(attrNamespaceURI, attrName);
      }
    } else {
      if (!toNode.hasAttribute(attrName)) {
        fromNode.removeAttribute(attrName);
      }
    }
  }
}

function morphdomFactory(morphAttrs) {
  return function morphdom(fromNode, toNode, options) {
    if (!options) options = {};
    if (typeof toNode === 'string') {
      var toNodeHtml = toNode;
      toNode = doc.createElement('html');
      toNode.innerHTML = toNodeHtml;
      toNode = toNode.firstChild;
    } else if (toNode.nodeType === DOCUMENT_FRAGMENT_NODE) {
      toNode = toNode.firstElementChild;
    }

    var getNodeKey = options.getNodeKey || defaultGetNodeKey;
    var onBeforeNodeAdded = options.onBeforeNodeAdded || function() {};
    var onNodeAdded = options.onNodeAdded || function() {};
    var onBeforeElUpdated = options.onBeforeElUpdated || function() {};
    var onElUpdated = options.onElUpdated || function() {};
    var onBeforeNodeDiscarded = options.onBeforeNodeDiscarded || function() {};
    var onNodeDiscarded = options.onNodeDiscarded || function() {};
    var onBeforeElChildrenUpdated = options.onBeforeElChildrenUpdated || function() {};
    var skipFromChildren = options.skipFromChildren || function() {};
    var addChild = options.addChild || function(parent, child) {
      return parent.appendChild(child);
    };
    var childrenOnly = options.childrenOnly === true;

    var fromNodesLookup = Object.create(null);
    var keyedRemovalList = [];

    function addKeyedRemoval(key) {
      keyedRemovalList.push(key);
    }

    function walkDiscardedChildNodes(node, skipKeyedNodes) {
      if (node.nodeType === ELEMENT_NODE) {
        var curChild = node.firstChild;
        while (curChild) {
          var key = getNodeKey(curChild);
          if (skipKeyedNodes && key) {
            addKeyedRemoval(key);
          } else {
            onNodeDiscarded(curChild);
            if (curChild.firstChild) {
              walkDiscardedChildNodes(curChild, skipKeyedNodes);
            }
          }
          curChild = curChild.nextSibling;
        }
      }
    }

    function removeNode(node, parentNode, skipKeyedNodes) {
      if (onBeforeNodeDiscarded(node) === false) return;
      if (parentNode) parentNode.removeChild(node);
      onNodeDiscarded(node);
      walkDiscardedChildNodes(node, skipKeyedNodes);
    }

    function indexTree(node) {
      if (node.nodeType === ELEMENT_NODE || node.nodeType === DOCUMENT_FRAGMENT_NODE) {
        var curChild = node.firstChild;
        while (curChild) {
          var key = getNodeKey(curChild);
          if (key) fromNodesLookup[key] = curChild;
          indexTree(curChild);
          curChild = curChild.nextSibling;
        }
      }
    }

    indexTree(fromNode);

    function handleNodeAdded(el) {
      onNodeAdded(el);
      var curChild = el.firstChild;
      while (curChild) {
        var key = getNodeKey(curChild);
        if (key) {
          var unmatchedFromEl = fromNodesLookup[key];
          if (unmatchedFromEl && compareNodeNames(curChild, unmatchedFromEl)) {
            curChild.parentNode.replaceChild(unmatchedFromEl, curChild);
            morphEl(unmatchedFromEl, curChild);
          } else {
            handleNodeAdded(curChild);
          }
        } else {
          handleNodeAdded(curChild);
        }
        curChild = curChild.nextSibling;
      }
    }

    function cleanupFromEl(fromEl, curFromNodeChild) {
      while (curFromNodeChild) {
        var fromNextSibling = curFromNodeChild.nextSibling;
        var curFromNodeKey = getNodeKey(curFromNodeChild);

        if (curFromNodeKey) {
          addKeyedRemoval(curFromNodeKey);
        } else {
          removeNode(curFromNodeChild, fromEl, true);
        }

        curFromNodeChild = fromNextSibling;
      }
    }

    function morphEl(fromEl, toEl, childrenOnly) {
      var toElKey = getNodeKey(toEl);
      if (toElKey) delete fromNodesLookup[toElKey];

      if (!childrenOnly) {
        var beforeUpdateResult = onBeforeElUpdated(fromEl, toEl);
        if (beforeUpdateResult === false) return;
        if (beforeUpdateResult instanceof HTMLElement) {
          fromEl = beforeUpdateResult;
          indexTree(fromEl);
        }

        morphAttrs(fromEl, toEl);
        onElUpdated(fromEl);

        if (onBeforeElChildrenUpdated(fromEl, toEl) === false) return;
      }

      if (fromEl.nodeName !== 'TEXTAREA') {
        morphChildren(fromEl, toEl);
      } else {
        specialElHandlers.TEXTAREA(fromEl, toEl);
      }
    }

    function morphChildren(fromEl, toEl) {
      var skipFrom = skipFromChildren(fromEl, toEl);
      var curToNodeChild = toEl.firstChild;
      var curFromNodeChild = fromEl.firstChild;

      outer: while (curToNodeChild) {
        var toNextSibling = curToNodeChild.nextSibling;
        var curToNodeKey = getNodeKey(curToNodeChild);

        while (!skipFrom && curFromNodeChild) {
          var fromNextSibling = curFromNodeChild.nextSibling;
          var curFromNodeKey = getNodeKey(curFromNodeChild);
          var curFromNodeType = curFromNodeChild.nodeType;

          var isCompatible = undefined;

          if (curFromNodeType === curToNodeChild.nodeType) {
            if (curFromNodeType === ELEMENT_NODE) {
              if (curToNodeKey) {
                if (curToNodeKey !== curFromNodeKey) {
                  var matchingFromEl = fromNodesLookup[curToNodeKey];
                  if (matchingFromEl && compareNodeNames(curFromNodeChild, matchingFromEl)) {
                    fromEl.insertBefore(matchingFromEl, curFromNodeChild);
                    if (curFromNodeKey) {
                      addKeyedRemoval(curFromNodeKey);
                    } else {
                      removeNode(curFromNodeChild, fromEl, true);
                    }
                    curFromNodeChild = matchingFromEl;
                    curFromNodeKey = getNodeKey(curFromNodeChild);
                  } else {
                    isCompatible = false;
                  }
                }
              } else if (curFromNodeKey) {
                isCompatible = false;
              }

              isCompatible = isCompatible !== false && compareNodeNames(curFromNodeChild, curToNodeChild);
              if (isCompatible) {
                morphEl(curFromNodeChild, curToNodeChild);
              }
            } else if (curFromNodeType === TEXT_NODE || curFromNodeType === COMMENT_NODE) {
              if (curFromNodeChild.nodeValue !== curToNodeChild.nodeValue) {
                curFromNodeChild.nodeValue = curToNodeChild.nodeValue;
              }
            }
          }

          if (isCompatible) {
            curToNodeChild = toNextSibling;
            curFromNodeChild = fromNextSibling;
            continue outer;
          }

          if (curFromNodeKey) {
            addKeyedRemoval(curFromNodeKey);
          } else {
            removeNode(curFromNodeChild, fromEl, true);
          }

          curFromNodeChild = fromNextSibling;
        }

        if (curToNodeKey && fromNodesLookup[curToNodeKey] && compareNodeNames(fromNodesLookup[curToNodeKey], curToNodeChild)) {
          var matchingFromEl = fromNodesLookup[curToNodeKey];
          if (!skipFrom) addChild(fromEl, matchingFromEl);
          morphEl(matchingFromEl, curToNodeChild);
        } else {
          var onBeforeNodeAddedResult = onBeforeNodeAdded(curToNodeChild);
          if (onBeforeNodeAddedResult !== false) {
            if (onBeforeNodeAddedResult) curToNodeChild = onBeforeNodeAddedResult;
            if (curToNodeChild.actualize) {
              curToNodeChild = curToNodeChild.actualize(doc);
            }
            addChild(fromEl, curToNodeChild);
            handleNodeAdded(curToNodeChild);
          }
        }

        curToNodeChild = toNextSibling;
        curFromNodeChild = fromNextSibling;
      }

      cleanupFromEl(fromEl, curFromNodeChild);
    }

    var morphedNode = fromNode;
    var morphedNodeType = morphedNode.nodeType;
    var toNodeType = toNode.nodeType;

    if (!childrenOnly) {
      if (morphedNodeType === ELEMENT_NODE) {
        if (toNodeType === ELEMENT_NODE && !compareNodeNames(fromNode, toNode)) {
          onBeforeNodeDiscarded(fromNode);
          onNodeDiscarded(fromNode);
          morphedNode = moveChildren(fromNode, createElementNS(toNode.nodeName, toNode.namespaceURI));
        }
      } else if (morphedNodeType === TEXT_NODE || morphedNodeType === COMMENT_NODE) {
        if (toNodeType === morphedNodeType && morphedNode.nodeValue !== toNode.nodeValue) {
          morphedNode.nodeValue = toNode.nodeValue;
          return morphedNode;
        } else {
          morphedNode = toNode;
        }
      }
    }

    if (morphedNode === toNode) {
      onNodeDiscarded(fromNode);
    } else {
      if (toNode.isSameNode && toNode.isSameNode(morphedNode)) return;
      morphEl(morphedNode, toNode, childrenOnly);

      if (keyedRemovalList.length) {
        for (var i = 0, len = keyedRemovalList.length; i < len; i++) {
          var elToRemove = fromNodesLookup[keyedRemovalList[i]];
          if (elToRemove) {
            removeNode(elToRemove, elToRemove.parentNode, false);
          }
        }
      }
    }

    if (!childrenOnly && morphedNode !== fromNode && fromNode.parentNode) {
      if (morphedNode.actualize) {
        morphedNode = morphedNode.actualize(doc);
      }
      fromNode.parentNode.replaceChild(morphedNode, fromNode);
    }

    return morphedNode;
  };
}

export default morphdomFactory(morphAttrs);