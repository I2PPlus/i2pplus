/**
 * @module morphdom
 * @file Fast and lightweight DOM diffing/patching library.
 * @description morphdom provides efficient DOM updates by diffing and patching changes.
 * @see {@link https://github.com/patrick-steele-idem/morphdom}
 * @license MIT
 *
 * This is an I2P+ fork.  Table-section handling (thead/tbody/tfoot matched by
 * tag name, never moved) was added to prevent the browser HTML parser from
 * restructuring tables on refresh, which detaches <tfoot> and breaks colspan.
 */
'use strict';

const doc = document;
const DOCUMENT_FRAGMENT_NODE = 11;
const ELEMENT_NODE = 1;
const TEXT_NODE = 3;
const COMMENT_NODE = 8;

const NS_XHTML = 'http://www.w3.org/1999/xhtml';

/**
 * True when a string is whitespace-only (empty, spaces, newlines, nbsp).
 * @param {string} value - The text value to check.
 * @returns {boolean} True for whitespace-only text.
 */
function isWsOnlyText(value) {
  return /^\s*$/.test(value);
}

/**
 * True when an element is inside a context where whitespace-only text nodes
 * are rendered (pre-formatted or raw-text content). In every other context
 * whitespace-only text nodes are collapsed by CSS and can be ignored when
 * diffing.
 * @param {Element} el - The element to check.
 * @returns {boolean} True inside a pre-like context.
 */
function inPreContext(el) {
  while (el) {
    const tag = el.tagName;
    if (tag === 'PRE' || tag === 'TEXTAREA' || tag === 'SCRIPT' || tag === 'STYLE') {
      return true;
    }
    el = el.parentNode;
  }
  return false;
}


/**
 * Default function to get a node's key for identification.
 * @param {Node} node - The DOM node to get a key for.
 * @returns {string|null} The node's id attribute or id property, or null if not found.
 */
function defaultGetNodeKey(node) {
  return node && ((node.getAttribute && node.getAttribute('id')) || node.id);
}

/**
 * Compare node names, handling case-insensitive comparison for HTML elements.
 * @param {Element} fromEl - The source element.
 * @param {Element} toEl - The target element.
 * @returns {boolean} True if node names match (case-insensitive for HTML).
 */
function compareNodeNames(fromEl, toEl) {
  const fromNodeName = fromEl.nodeName;
  const toNodeName = toEl.nodeName;

  if (fromNodeName === toNodeName) { return true; }

  const fromCodeStart = fromNodeName.charCodeAt(0);
  const toCodeStart = toNodeName.charCodeAt(0);

  if (fromCodeStart <= 90 && toCodeStart >= 97) {
    return fromNodeName === toNodeName.toUpperCase();
  }
  if (toCodeStart <= 90 && fromCodeStart >= 97) {
    return toNodeName === fromNodeName.toUpperCase();
  }
  return false;
}

/**
 * Whether a node is a table-section element (thead/tbody/tfoot).
 * @param {Node} node - The node to check.
 * @returns {boolean} True if the node is a table section.
 */
function isTableSection(node) {
  if (!node || node.nodeType !== ELEMENT_NODE) { return false; }
  const name = node.nodeName;
  return name === "THEAD" || name === "TBODY" || name === "TFOOT";
}

/**
 * Find a table-section element by tag name within a table.
 * @param {Element} fromEl - The live table element.
 * @param {string} tagName - The section tag name to find.
 * @returns {Element|null} The matching element, or null.
 */
function findTableSection(fromEl, tagName) {
  let child = fromEl.firstChild;
  while (child) {
    if (child.nodeType === ELEMENT_NODE && child.nodeName === tagName) { return child; }
    child = child.nextSibling;
  }
  return null;
}

/**
 * Create an element with optional namespace.
 * @param {string} name - The tag name.
 * @param {string} [namespaceURI] - The namespace URI.
 * @returns {Element} The created element.
 */
function createElementNS(name, namespaceURI) {
  return !namespaceURI || namespaceURI === NS_XHTML
    ? doc.createElement(name)
    : doc.createElementNS(namespaceURI, name);
}

/**
 * Move all children from one element to another.
 * @param {Element} fromEl - The source element.
 * @param {Element} toEl - The target element.
 * @returns {Element} The target element with moved children.
 */
function moveChildren(fromEl, toEl) {
  while (fromEl.firstChild) {
    toEl.appendChild(fromEl.firstChild);
  }
  return toEl;
}

/**
 * Synchronize a boolean attribute/property between elements.
 * @param {Element} fromEl - The source element.
 * @param {Element} toEl - The target element.
 * @param {string} name - The attribute/property name.
 */
function syncBooleanAttrProp(fromEl, toEl, name) {
  if (fromEl[name] !== toEl[name]) {
    fromEl[name] = toEl[name];
    fromEl[name] ? fromEl.setAttribute(name, '') : fromEl.removeAttribute(name);
  }
}

/**
 * Special element handlers for textarea, select, and input elements.
 * @type {Object.<string, function(Element, Element)>}
 */
const specialElHandlers = {
  /**
   * Handle textarea element synchronization.
   * @param {Element} fromEl - The source textarea element.
   * @param {Element} toEl - The target textarea element.
   */
  TEXTAREA: function(fromEl, toEl) {
    if (fromEl.value !== toEl.value) {
      fromEl.value = toEl.value;
    }
    const firstChild = fromEl.firstChild;
    if (firstChild && firstChild.nodeValue !== toEl.value) {
      firstChild.nodeValue = toEl.value;
    }
  },
  /**
   * Handle select element synchronization.
   * @param {Element} fromEl - The source select element.
   * @param {Element} toEl - The target select element.
   */
  SELECT: function(fromEl, toEl) {
    if (!toEl.hasAttribute('multiple')) {
      let curChild = fromEl.firstChild;
      let i = 0;
      let selected = -1;
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
  /**
   * Handle input element synchronization.
   * @param {Element} fromEl - The source input element.
   * @param {Element} toEl - The target input element.
   */
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

/**
 * Synchronize attributes from one node to another.
 * @param {Element} fromNode - The source element.
 * @param {Element} toNode - The target element.
 */
function morphAttrs(fromNode, toNode) {
  const toNodeAttrs = toNode.attributes;
  let attr, attrName, attrNamespaceURI, attrValue;

  for (let i = toNodeAttrs.length - 1; i >= 0; i--) {
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

  const fromNodeAttrs = fromNode.attributes;
  for (let d = fromNodeAttrs.length - 1; d >= 0; d--) {
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

/**
 * Factory that creates the morphdom function with custom attribute morphing.
 * @param {function(Element, Element)} morphAttrs - Function to synchronize attributes.
 * @returns {function(Node|string, Node|string, Object=): Node} The morphdom function.
 */
function morphdomFactory(morphAttrs) {
  /**
   * Morph the DOM from one node to another.
   * @param {Node|string} fromNode - The source node or HTML string.
   * @param {Node|string} toNode - The target node or HTML string.
   * @param {Object} [options] - Configuration options.
   * @param {function(Node): string} [options.getNodeKey] - Function to get a node's key.
   * @param {function(Node)} [options.onBeforeNodeAdded] - Called before a node is added.
   * @param {function(Node)} [options.onNodeAdded] - Called after a node is added.
   * @param {function(Element, Element)} [options.onBeforeElUpdated] - Called before an element is updated.
   * @param {function(Element)} [options.onElUpdated] - Called after an element is updated.
   * @param {function(Node)} [options.onBeforeNodeDiscarded] - Called before a node is discarded.
   * @param {function(Node)} [options.onNodeDiscarded] - Called after a node is discarded.
   * @param {function(Element, Element)} [options.onBeforeElChildrenUpdated] - Called before element children are updated.
   * @param {function(Element, Element)} [options.skipFromChildren] - Skip processing children of this element.
   * @param {function(Node, Node)} [options.addChild] - Custom function to add a child to a parent.
   * @param {boolean} [options.childrenOnly=false] - Only morph children, not the element itself.
   * @returns {Node} The morphed node.
   */
  return function morphdom(fromNode, toNode, options) {
    if (!options) { options = {}; }
    if (typeof toNode === 'string') {
      const toNodeHtml = toNode;
      toNode = doc.createElement('html');
      toNode.innerHTML = toNodeHtml;
      toNode = toNode.firstChild;
    } else if (toNode.nodeType === DOCUMENT_FRAGMENT_NODE) {
      toNode = toNode.firstElementChild;
    }

    const getNodeKey = options.getNodeKey || defaultGetNodeKey;
    const onBeforeNodeAdded = options.onBeforeNodeAdded || function() {};
    const onNodeAdded = options.onNodeAdded || function() {};
    const onBeforeElUpdated = options.onBeforeElUpdated || function() {};
    const onElUpdated = options.onElUpdated || function() {};
    const onBeforeNodeDiscarded = options.onBeforeNodeDiscarded || function() {};
    const onNodeDiscarded = options.onNodeDiscarded || function() {};
    const onBeforeElChildrenUpdated = options.onBeforeElChildrenUpdated || function() {};
    const skipFromChildren = options.skipFromChildren || function() {};
    const addChild = options.addChild || function(parent, child) {
      return parent.appendChild(child);
    };
    const childrenOnly = options.childrenOnly === true;

    const fromNodesLookup = Object.create(null);
    const keyedRemovalList = [];

    function addKeyedRemoval(key) {
      keyedRemovalList.push(key);
    }

    function walkDiscardedChildNodes(node, skipKeyedNodes) {
      if (node.nodeType === ELEMENT_NODE) {
        let curChild = node.firstChild;
        while (curChild) {
          const key = getNodeKey(curChild);
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
      if (onBeforeNodeDiscarded(node) === false) { return; }
      if (parentNode) { parentNode.removeChild(node); }
      onNodeDiscarded(node);
      walkDiscardedChildNodes(node, skipKeyedNodes);
    }

    function indexTree(node) {
      if (node.nodeType === ELEMENT_NODE || node.nodeType === DOCUMENT_FRAGMENT_NODE) {
        let curChild = node.firstChild;
        while (curChild) {
          const key = getNodeKey(curChild);
          if (key) { fromNodesLookup[key] = curChild; }
          indexTree(curChild);
          curChild = curChild.nextSibling;
        }
      }
    }

    indexTree(fromNode);

    function handleNodeAdded(el) {
      onNodeAdded(el);
      let curChild = el.firstChild;
      while (curChild) {
        const key = getNodeKey(curChild);
        if (key) {
          const unmatchedFromEl = fromNodesLookup[key];
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
        const fromNextSibling = curFromNodeChild.nextSibling;
        const curFromNodeKey = getNodeKey(curFromNodeChild);

        if (curFromNodeKey) {
          addKeyedRemoval(curFromNodeKey);
        } else if (!(curFromNodeChild.nodeType === TEXT_NODE && !inPreContext(fromEl) && isWsOnlyText(curFromNodeChild.nodeValue))) {
          removeNode(curFromNodeChild, fromEl, true);
        }

        curFromNodeChild = fromNextSibling;
      }
    }

    function morphEl(fromEl, toEl, childrenOnly) {
      const toElKey = getNodeKey(toEl);
      if (toElKey) { delete fromNodesLookup[toElKey]; }

      if (!childrenOnly) {
        const beforeUpdateResult = onBeforeElUpdated(fromEl, toEl);
        if (beforeUpdateResult === false) { return; }
        if (beforeUpdateResult instanceof HTMLElement) {
          fromEl = beforeUpdateResult;
          indexTree(fromEl);
        }

        morphAttrs(fromEl, toEl);
        onElUpdated(fromEl);

        if (onBeforeElChildrenUpdated(fromEl, toEl) === false) { return; }
      }

      if (fromEl.nodeName !== 'TEXTAREA') {
        morphChildren(fromEl, toEl);
      } else {
        specialElHandlers.TEXTAREA(fromEl, toEl);
      }
    }

    function morphChildren(fromEl, toEl) {
      const skipFrom = skipFromChildren(fromEl, toEl);
      let curToNodeChild = toEl.firstChild;
      let curFromNodeChild = fromEl.firstChild;
      let lastSectionNode = null;

      outer: while (curToNodeChild) {
        const toNextSibling = curToNodeChild.nextSibling;
        let fromNextSibling;
        const curToNodeKey = getNodeKey(curToNodeChild);

        // Table sections (thead/tbody/tfoot) must be matched by tag name and
        // updated in place.  Moving them via insertBefore/appendChild triggers
        // the browser HTML parser to restructure the table, detaching the
        // section and breaking colspan/layout (e.g. tfoot bandwidth footer).
        if (fromEl.nodeName === "TABLE" && isTableSection(curToNodeChild)) {
          const existing = findTableSection(fromEl, curToNodeChild.nodeName);
          if (existing) {
            morphEl(existing, curToNodeChild);
            lastSectionNode = existing;
          } else {
            // Section missing in the live table — insert it after the
            // previously handled section so section order matches the
            // response (response sections come in canonical thead/tbody/tfoot
            // order, so this preserves the document order).
            if (lastSectionNode) {
              fromEl.insertBefore(curToNodeChild, lastSectionNode.nextSibling);
            } else {
              fromEl.insertBefore(curToNodeChild, fromEl.firstChild);
            }
            lastSectionNode = curToNodeChild;
            handleNodeAdded(curToNodeChild);
          }
          // Advance curFromNodeChild past every table section and the
          // whitespace-only text nodes between them — sections are all handled
          // by tag name above, so the general loop and cleanupFromEl must not
          // treat them as leftover children (a live table parsed from HTML
          // keeps "\n" text nodes between thead/tbody/tfoot).
          while (curFromNodeChild &&
                 (isTableSection(curFromNodeChild) ||
                  (curFromNodeChild.nodeType === TEXT_NODE && isWsOnlyText(curFromNodeChild.nodeValue)))) {
            curFromNodeChild = curFromNodeChild.nextSibling;
          }
          curToNodeChild = toNextSibling;
          continue outer;
        }

        // Whitespace-only text nodes never render outside pre-like contexts,
        // and the VDOM side may split or merge them differently than the
        // live DOM (parsers differ in text-node merging). Skip them so they
        // neither trigger writes nor insert/remove churn.
        if (!inPreContext(fromEl) && curToNodeChild.nodeType === TEXT_NODE && isWsOnlyText(curToNodeChild.nodeValue)) {
          curToNodeChild = toNextSibling;
          continue outer;
        }

        if (!skipFrom) {
          while (curFromNodeChild) {
            fromNextSibling = curFromNodeChild.nextSibling;
            let curFromNodeKey = getNodeKey(curFromNodeChild);
            const curFromNodeType = curFromNodeChild.nodeType;

            // Skip table sections in the source — they're matched by tag name
            // above and must not be removed as "incompatible" children.
            if (fromEl.nodeName === "TABLE" && isTableSection(curFromNodeChild)) {
              curFromNodeChild = fromNextSibling;
              continue;
            }

            let isCompatible = undefined;

            if (curFromNodeType === curToNodeChild.nodeType) {
              if (curFromNodeType === ELEMENT_NODE) {
                if (curToNodeKey) {
                  if (curToNodeKey !== curFromNodeKey) {
                    const matchingFromEl = fromNodesLookup[curToNodeKey];
                    if (matchingFromEl && fromNextSibling === matchingFromEl) {
                      // The keyed match is the very next from-node, so it will be
                      // paired on the next pass. Moving it now would re-order the
                      // pair and the next pass would move it back.
                      isCompatible = false;
                    } else if (matchingFromEl && compareNodeNames(curFromNodeChild, matchingFromEl)) {
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
                // Same node type: the value is written where the node stands, and
                // the match counts so neither node is discarded.
                isCompatible = true;
                if (curFromNodeChild.nodeValue !== curToNodeChild.nodeValue &&
                    !(curFromNodeType === TEXT_NODE && !inPreContext(fromEl) &&
                      isWsOnlyText(curFromNodeChild.nodeValue) && isWsOnlyText(curToNodeChild.nodeValue))) {
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
            } else if (!(curFromNodeChild.nodeType === TEXT_NODE && !inPreContext(fromEl) && isWsOnlyText(curFromNodeChild.nodeValue))) {
              removeNode(curFromNodeChild, fromEl, true);
            }

            curFromNodeChild = fromNextSibling;
          }
        }

        if (curToNodeKey && fromNodesLookup[curToNodeKey] && compareNodeNames(fromNodesLookup[curToNodeKey], curToNodeChild)) {
          const matchingFromEl = fromNodesLookup[curToNodeKey];
          if (!skipFrom) { addChild(fromEl, matchingFromEl); }
          morphEl(matchingFromEl, curToNodeChild);
        } else {
          const onBeforeNodeAddedResult = onBeforeNodeAdded(curToNodeChild);
          if (onBeforeNodeAddedResult !== false) {
            if (onBeforeNodeAddedResult) { curToNodeChild = onBeforeNodeAddedResult; }
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

    let morphedNode = fromNode;
    const morphedNodeType = morphedNode.nodeType;
    const toNodeType = toNode.nodeType;

    if (!childrenOnly) {
      if (morphedNodeType === ELEMENT_NODE) {
        if (toNodeType === ELEMENT_NODE && !compareNodeNames(fromNode, toNode)) {
          onBeforeNodeDiscarded(fromNode);
          onNodeDiscarded(fromNode);
          morphedNode = moveChildren(fromNode, createElementNS(toNode.nodeName, toNode.namespaceURI));
        }
      } else if (morphedNodeType === TEXT_NODE || morphedNodeType === COMMENT_NODE) {
        if (toNodeType === morphedNodeType && morphedNode.nodeValue !== toNode.nodeValue &&
            !(morphedNodeType === TEXT_NODE && !inPreContext(morphedNode.parentNode) &&
              isWsOnlyText(morphedNode.nodeValue) && isWsOnlyText(toNode.nodeValue))) {
          morphedNode.nodeValue = toNode.nodeValue;
          return morphedNode;
        }
        morphedNode = toNode;
      }
    }

    if (morphedNode === toNode) {
      onNodeDiscarded(fromNode);
    } else {
      if (toNode.isSameNode && toNode.isSameNode(morphedNode)) { return; }
      morphEl(morphedNode, toNode, childrenOnly);

      if (keyedRemovalList.length) {
        for (let i = 0, len = keyedRemovalList.length; i < len; i++) {
          const elToRemove = fromNodesLookup[keyedRemovalList[i]];
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
