/**
 * @module routerControl
 * @description Runs the sidebar's restart/shutdown form without reloading the
 * page, and brings the router-control block current in place, which is where
 * the countdown and the cancel button appear.
 *
 * The submission is an in-page request to xhrcontrol.jsp, the endpoint that runs
 * the action and renders the router-control block on its own: about a kilobyte,
 * no images, no scripts. The response is the block, so the page is patched from
 * it directly. A browser that never runs this script keeps the plain top-level
 * POST the form carries, which reloads the page and runs the action, so a
 * missing script cannot break the control it exists to make quieter.
 *
 * A frame as the submission target was the first shape of this, and it is gone:
 * Chrome injects its own per-frame scripts - autofill and the password manager
 * operate inside iframes - into a frame that runs no script, and every
 * submission logged "Blocked script execution in 'about:blank'" for it. A
 * request has no document to inject into, so that class of noise cannot exist.
 *
 * A graceful restart stops the router's server tunnels on their configured
 * delays. The server publishes that deferral synchronously and does the waiting
 * on a worker thread, so the request that runs the action answers promptly; and
 * the block is looked at on a short loop that starts when the button is pressed,
 * not when that request answers, because a countdown that never arrives is
 * indistinguishable from a button that never ran.
 *
 * What a failed exchange must never do is leave the page silent: the countdown
 * would appear nowhere and the button would look dead. So every path that
 * cannot bring the block current - a request that fails, a response without the
 * block, a block a cosmetic filter has hidden by class - reloads the page
 * instead, which is the state the user needs to see.
 *
 * Two things follow from the console serving /js/*.js as
 * "private, max-age=2628000, immutable" (WebAppStarter): a returning browser
 * runs the copy of a script URL it cached up to 30 days ago and never
 * revalidates it. So this file imports only files that ship with it, and a
 * change to this behaviour has to land under new file names, since the old URLs
 * are pinned in every returning browser's cache.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import {
  BOX_ID,
  FORM_ID,
  POLL_INTERVAL_MS,
  actionFrom,
  controlURL,
  formBody,
  isRendered,
  isSettled,
  isStaleGeneration,
  leavesPending,
  shouldPollAgain
} from "./routerControlState.js";

/** What the last submission left to wait for, null when nothing is in flight. */
let expected = null;

/** Looks made for the current submission. */
let polls = 0;

/** The value of the last action button clicked, for browsers with no submitter. */
let clicked = "";

/** Bumped by every submission, so a response to a replaced one is recognised. */
let generation = 0;

/**
 * The action a submission carries: the value of the button that was clicked,
 * which is what the server reads. The form's buttons are all named "action", so
 * the form's own action property is a list of them and yields the first one
 * whichever was used; a recorded click is the next best thing, and the first
 * button is what a keyboard submit with no click acts on.
 * @param {HTMLFormElement} form - the form being submitted
 * @param {Element|null} submitter - the button that submitted the form, if known
 * @returns {string} the action name, empty if the form carries none
 */
function actionOf(form, submitter) {
  const submitterValue = submitter && submitter.name === "action" ? submitter.value : "";
  const buttons = form.querySelectorAll("button[name=action]");
  const first = buttons.length ? buttons[0].value : "";
  return actionFrom(submitterValue, clicked, first);
}

/**
 * The fields the form would have posted, with the action set to the button that
 * was used. Only the value-bearing controls are read: the form's buttons share
 * the name "action", and the one that was pressed is known from the submitter,
 * so they are added from there rather than from the markup.
 * @param {HTMLFormElement} form - the form being submitted
 * @param {string} action - the action the submission carries
 * @returns {Array<Array<string>>} [name, value] pairs, in form order
 */
function fieldsOf(form, action) {
  const fields = [];
  const controls = form.querySelectorAll("input, select, textarea");
  for (const el of controls) {
    const type = (el.type || "").toLowerCase();
    if (!el.name || el.disabled
        || (type === "checkbox" || type === "radio") && !el.checked) {
      continue;
    }
    fields.push([el.name, el.value]);
  }
  if (action) {fields.push(["action", action]);}
  return fields;
}

/**
 * The router-control block inside a response, as a node of a detached document.
 * @param {string} text - the response body
 * @returns {Element|null} the block, null when the response does not carry one
 */
function boxFrom(text) {
  return new DOMParser().parseFromString(text, "text/html").getElementById(BOX_ID);
}

/**
 * Patch the page from a response and decide what to do next: stop when the
 * block reflects the submitted action, look again while it does not, and reload
 * when the block cannot be shown at all.
 * @param {string} text - a response body carrying the router-control block
 * @param {number} mine - the generation this response belongs to
 * @returns {void}
 */
function apply(text, mine) {
  if (isStaleGeneration(mine, generation)) {return;}
  const box = document.getElementById(BOX_ID);
  const fresh = boxFrom(text);
  if (!box || !fresh) {
    location.reload();
    return;
  }
  box.innerHTML = fresh.innerHTML;
  // The block is on the page, but a cosmetic filter can hide it by class with
  // display:none, and a countdown nobody can see is the silent control this
  // file exists to remove. A reload puts the page back as the server renders
  // it, which is the state the user needs to see.
  if (!isRendered(box.getClientRects ? box.getClientRects().length : 1)) {
    expected = null;
    location.reload();
    return;
  }
  // Nothing is left to wait for: a response that arrives after the control has
  // settled brings the block current and stops there.
  if (expected === null) {return;}
  polls += 1;
  const settled = isSettled(fresh, expected);
  if (settled || !shouldPollAgain(settled, polls)) {expected = null;}
  else {setTimeout(refresh, POLL_INTERVAL_MS);}
}

/**
 * Make one exchange with the block's endpoint and patch the page from the answer.
 * Reloading the page is the fallback for everything that cannot bring the block
 * current: a request that fails, a response that is not the block, a block a
 * cosmetic filter has hidden. A control that ran and said nothing is
 * indistinguishable from a control that did not run.
 *
 * The response is tagged with the submission it belongs to. A response to a
 * submission the user has replaced is dropped: it describes a state the page has
 * moved past, and patching it in would show a countdown the user has already
 * cancelled.
 * @param {string} method - POST to run an action, GET to look at the block
 * @param {?string} body - the urlencoded fields for a POST, null for a look
 * @param {number} mine - the generation this request belongs to
 * @returns {void}
 */
function request(method, body, mine) {
  const xhr = new XMLHttpRequest();
  xhr.open(method, controlURL(location.pathname), true);
  if (body !== null) {xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");}
  xhr.onload = function() {
    if (xhr.status === 200 && xhr.responseText) {apply(xhr.responseText, mine);}
    else {location.reload();}
  };
  xhr.onerror = function() {
    if (isStaleGeneration(mine, generation)) {return;}
    location.reload();
  };
  xhr.send(body);
}

/**
 * Look at the block again, for the actions whose state the server reaches a
 * moment after answering.
 * @returns {void}
 */
function refresh() {
  if (expected === null) {return;}
  request("GET", null, generation);
}

/**
 * Remember which action button is being used, for the browsers that do not
 * report the submitter of a submit event.
 * @param {Event} event - the click event
 * @returns {void}
 */
function onClick(event) {
  const node = event.target;
  const button = node && node.closest ? node.closest("button[name=action]") : null;
  clicked = button && button.closest("#" + FORM_ID) ? button.value : "";
}

/**
 * Take one submit over: run the action in place and start watching the block.
 *
 * Delegated on the document and run in the capture phase, because the sidebar
 * refresh replaces this form every few seconds: a listener bound to the form
 * element is gone with the element it was bound to.
 *
 * The watching starts at the submit, not when the submission answers. A
 * graceful restart stops the router's server tunnels on their configured
 * delays, and the request that runs the action answers when that is under way,
 * so a control that waited on it would show the countdown only when the server
 * chose to show it. The block is looked at on the loop from the moment the
 * button was pressed, and the submission's own response is one more look.
 *
 * The form's own action attribute is left alone, so a browser that never runs
 * this file posts to the page it was rendered on, as it always has.
 * @param {Event} event - the submit event
 * @returns {void}
 */
function onSubmit(event) {
  const form = event.target;
  if (!form || form.id !== FORM_ID) {return;}
  event.preventDefault();
  const action = actionOf(form, event.submitter);
  generation += 1;
  const mine = generation;
  expected = leavesPending(action);
  polls = 0;
  clicked = "";
  request("POST", formBody(fieldsOf(form, action)), mine);
  if (expected) {setTimeout(refresh, POLL_INTERVAL_MS);}}

document.addEventListener("click", onClick, true);
document.addEventListener("submit", onSubmit, true);
