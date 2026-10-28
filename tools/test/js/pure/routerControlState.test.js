/**
 * Tier-2 pure tests for the decisions behind the sidebar's restart/shutdown
 * control (apps/routerconsole/jsp/js/routerControl.js), which are kept in
 * routerControlState.js so they can be pinned without a browser.
 *
 * These pin the contract that makes the control reliable: the block is refreshed
 * against the action that was submitted, so a Cancel is settled when the status
 * heading is gone and a Restart is settled when it appears; the fetch loop stops
 * at the cap and leaves the block to the page's own refresh; the action is read
 * from the button that was used, not from the form's action property, which is a
 * list of every button in the form; the body carries the form's fields with the
 * action that was used; and the endpoint is the one that renders the block.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
  BOX_ID,
  CANCEL_ACTION,
  CONTROL_PATH,
  FORM_ID,
  MAX_POLLS,
  POLL_INTERVAL_MS,
  STATUS_ID,
  actionFrom,
  controlURL,
  formBody,
  isRendered,
  isSettled,
  isStaleGeneration,
  leavesPending,
  shouldPollAgain
} from "../../../../apps/routerconsole/jsp/js/routerControlState.js";

/**
 * A stand-in for the fetched block: querySelector answers from a fixed list of
 * the ids it contains, which is all isSettled looks at.
 *
 * @param {string[]} ids - the element ids present in the block
 * @returns {{querySelector: Function}} the stub block
 */
function boxWith(ids) {
  return {querySelector: (sel) => (ids.includes(sel.slice(1)) ? {} : null)};
}

test("the ids the control is built around are the ones the server renders", () => {
  assert.equal(FORM_ID, "sb_routerControl");
  assert.equal(BOX_ID, "sb_routerControlBox");
  assert.equal(STATUS_ID, "sb_shutdownStatus");
});

test("the block is submitted to and fetched from its own endpoint", () => {
  // Not the sidebar: the exchange carries the block, about a kilobyte, with no
  // images and no scripts in it.
  assert.equal(CONTROL_PATH, "/xhrcontrol.jsp");
  assert.equal(controlURL("/routerlogs"), "/xhrcontrol.jsp?requestURI=%2Frouterlogs");
  assert.equal(controlURL("/config/logging"), "/xhrcontrol.jsp?requestURI=%2Fconfig%2Flogging");
  // The path is a parameter value, so it is encoded, not concatenated.
  assert.equal(controlURL("/x?y=1"), "/xhrcontrol.jsp?requestURI=%2Fx%3Fy%3D1");
});

test("the body carries the form's fields and the action that was used", () => {
  assert.equal(formBody([["consoleNonce", "CN42"], ["action", "restart"]]),
               "consoleNonce=CN42&action=restart");
  // Names and values are encoded: the server reads them as form fields.
  assert.equal(formBody([["q", "a b&c=d"]]), "q=a%20b%26c%3Dd");
  // A field with no name is not a field the server can read.
  assert.equal(formBody([["", "x"], ["a", "1"]]), "a=1");
  // A missing value is an empty one, not the string "undefined".
  assert.equal(formBody([["action", null], ["n", undefined]]), "action=&n=");
  // Localized action labels go through the encoding intact.
  assert.equal(formBody([["action", "Neustart äöü"]]), "action=Neustart%20%C3%A4%C3%B6%C3%BC");
});

test("a block with layout boxes is on the page, one without is not", () => {
  // display:none, from a cosmetic filter hiding by class, leaves no boxes.
  assert.equal(isRendered(0), false);
  assert.equal(isRendered(1), true);
  assert.equal(isRendered(12), true);
});

test("every action except cancel leaves a pending shutdown to show", () => {
  for (const action of ["restart", "shutdown", "restartImmediate", "shutdownImmediate"]) {
    assert.equal(leavesPending(action), true, action);
  }
  assert.equal(leavesPending(CANCEL_ACTION), false);
  // An action that cannot be identified is treated as one that defers: the loop
  // then polls to its cap and stops, leaving the block to the page's own refresh,
  // which is the safe reading of an unknown submission.
  assert.equal(leavesPending(""), true);
});

test("a block is settled when its status heading matches what the action leaves", () => {
  const pending = boxWith([STATUS_ID]);
  const idle = boxWith([]);
  assert.equal(isSettled(pending, true), true);
  assert.equal(isSettled(idle, false), true);
  assert.equal(isSettled(pending, false), false);
  assert.equal(isSettled(idle, true), false);
});

test("a missing block is never settled", () => {
  assert.equal(isSettled(null, true), false);
  assert.equal(isSettled(null, false), false);
});

test("the loop repeats while the block lags the action, and stops at the cap", () => {
  assert.equal(shouldPollAgain(false, 0), true);
  assert.equal(shouldPollAgain(true, 0), false);
  assert.equal(shouldPollAgain(false, MAX_POLLS - 1), true);
  assert.equal(shouldPollAgain(false, MAX_POLLS), false);
  assert.equal(shouldPollAgain(true, MAX_POLLS), false);
});

test("the loop is bounded to a few seconds of polling", () => {
  assert.equal(POLL_INTERVAL_MS, 1000);
  assert.equal(MAX_POLLS, 15);
});

test("a response to a replaced submission is dropped", () => {
  assert.equal(isStaleGeneration(1, 1), false);
  assert.equal(isStaleGeneration(1, 2), true);
  assert.equal(isStaleGeneration(2, 1), true);
});

test("the action comes from the button used, not from the form's first button", () => {
  // The submitter is what the browser reports and what the server sees.
  assert.equal(actionFrom("shutdown", "restart", "restart"), "shutdown");
  // No submitter (older browser): the recorded click wins over the first button.
  assert.equal(actionFrom("", "shutdown", "restart"), "shutdown");
  // No click either (keyboard submit): the first button is the default button.
  assert.equal(actionFrom("", "", "restart"), "restart");
  assert.equal(actionFrom("", "", ""), "");
});
