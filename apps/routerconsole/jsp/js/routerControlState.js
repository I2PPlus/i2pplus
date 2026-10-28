/**
 * @module routerControlState
 * @description The decisions behind routerControl.js, kept free of the DOM so
 * they can be tested without a browser.
 *
 * The sidebar's restart/shutdown form is submitted by an in-page request so the
 * page is not reloaded, and the router-control block is then brought current
 * from the endpoint that renders it on its own. What "current" means is decided
 * here: which actions leave a shutdown pending, when a fetched block reflects
 * the state the submitted action left behind, when to stop looking, and when
 * the block is on the page in name only - hidden by a cosmetic filter from an
 * ad blocker - so the control has to fall back to a page reload.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** Id of the sidebar's restart/shutdown form. */
export const FORM_ID = "sb_routerControl";

/** Id of the block that is refreshed after a submission. */
export const BOX_ID = "sb_routerControlBox";

/**
 * The endpoint that runs the action and renders the router-control block on its
 * own, with no icons and no scripts, so the exchange carries about a kilobyte
 * and the page never renders the whole sidebar to read one block out of it.
 */
export const CONTROL_PATH = "/xhrcontrol.jsp";

/**
 * The status heading, present only while a shutdown is pending.
 */
export const STATUS_ID = "sb_shutdownStatus";

/** The action that clears a pending shutdown, returning the plain buttons. */
export const CANCEL_ACTION = "cancelShutdown";

/** Milliseconds between looks at the block. */
export const POLL_INTERVAL_MS = 1000;

/** Looks to make before giving up on the block reflecting the action. */
export const MAX_POLLS = 15;

/**
 * The action a submission carries, from the sources a browser offers.
 *
 * The submitter of the submit event is the button that was used, which is what
 * the server reads. Where a browser reports no submitter, a click recorded on
 * the button is the next best thing. Where there is neither, as on a keyboard
 * submit of a form whose buttons were never clicked, the form's first action
 * button is what the browser would have sent.
 * @param {string} submitterValue - the value of the submitting button, if known
 * @param {string} clickedValue - the value of the last action button clicked
 * @param {string} firstValue - the value of the form's first action button
 * @returns {string} the action the submission carries
 */
export function actionFrom(submitterValue, clickedValue, firstValue) {
  if (submitterValue) {return submitterValue;}
  if (clickedValue) {return clickedValue;}
  return firstValue || "";
}

/**
 * The body of the request that runs an action, from the fields the form carries.
 * @param {Array<Array<string>>} fields - [name, value] pairs, in form order
 * @returns {string} an application/x-www-form-urlencoded body
 */
export function formBody(fields) {
  const parts = [];
  for (const field of fields) {
    const name = field[0];
    if (!name) {continue;}
    parts.push(encodeURIComponent(name) + "=" + encodeURIComponent(field[1] == null ? "" : field[1]));
  }
  return parts.join("&");
}

/**
 * The URL of the router-control block, rendered on its own for one page.
 * @param {string} pathname - the page's location.pathname, the page the block's
 *                            form posts to when scripting is unavailable
 * @returns {string} the URL to submit to and to refresh the block from
 */
export function controlURL(pathname) {
  return CONTROL_PATH + "?requestURI=" + encodeURIComponent(pathname);
}

/**
 * Whether the submitted action leaves a state to wait for in the block.
 *
 * Everything except a cancel is treated as leaving something: a restart, a
 * shutdown, an action whose name was not recognised. Waiting is the safe
 * reading of an unknown submission, because the loop then looks until the block
 * stops changing and leaves it to the page's own refresh, rather than declaring
 * a state the server has not reached yet.
 * @param {string} action - the action the submission carries
 * @returns {boolean} true if the block is expected to change after this action
 */
export function leavesPending(action) {
  return action !== CANCEL_ACTION;
}

/**
 * Whether a fetched block reflects the state the submitted action left behind.
 *
 * A pending action is reflected by the status heading, which only a pending
 * shutdown renders. A cancelling action is reflected by its absence. A block
 * that is not there at all agrees with nothing, and is settled by nothing.
 * @param {Element|null} box - the router-control block as fetched, null when the
 *                             response does not carry one
 * @param {boolean} expected - true if a pending state is expected after the
 *                             submitted action, false if the plain buttons are
 * @returns {boolean} true if the block is current for the submission
 */
export function isSettled(box, expected) {
  if (!box) {return false;}
  return (box.querySelector("#" + STATUS_ID) !== null) === expected;
}

/**
 * Whether a response belongs to a submission the user has since replaced.
 * @param {number} mine - the generation the request was made for
 * @param {number} current - the generation now in flight
 * @returns {boolean} true if the response is superseded and must be dropped
 */
export function isStaleGeneration(mine, current) {
  return mine !== current;
}

/**
 * Whether an element is drawn on the page. A cosmetic filter from an ad blocker
 * hides by class with display:none, which leaves an element with no layout
 * boxes at all, so a block that was patched in but cannot be seen has to be
 * treated as not delivered.
 * @param {number} rectCount - the element's getClientRects().length
 * @returns {boolean} true if the element occupies layout space
 */
export function isRendered(rectCount) {
  return rectCount > 0;
}

/**
 * Whether to look at the block again. A block that has caught up needs no
 * further look; a block that has not is given the short loop, and no more.
 * @param {boolean} settled - whether the block reflects the submitted action
 * @param {number} polls - the looks made for this submission
 * @returns {boolean} true if another look is worth making
 */
export function shouldPollAgain(settled, polls) {
  return !settled && polls < MAX_POLLS;
}
