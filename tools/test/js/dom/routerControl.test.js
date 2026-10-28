/**
 * Tier-2 DOM contract tests for the sidebar's restart/shutdown control
 * (apps/routerconsole/jsp/js/routerControl.js), run against linkedom plus the
 * harness browser shim, with the network and the reload stubbed.
 *
 * These pin what the control promises: a submission is taken over, so the
 * browser's own navigation never runs; the action is run by a request to the
 * endpoint that renders the block on its own, carrying the form's fields and the
 * button that was used; the page is patched from the response, so the countdown
 * arrives in place; the form's own attributes are left alone, so a browser that
 * never runs the module posts to the page it was rendered on; an exchange that
 * cannot deliver a visible block reloads the page, because a control that ran
 * and said nothing is indistinguishable from one that did not run; and once the
 * block reflects the submitted action the watching stops.
 *
 * The shim lives on globalThis for the whole process, so every module copy
 * imported here shares one document and its listeners accumulate. Each case
 * therefore measures the traffic it caused from a baseline taken after its own
 * import, and never an absolute count.
 *
 * Timers are recorded, never fired by the harness, and fired by hand through
 * fire(), so a case decides exactly when the next look at the block happens.
 * The cap on the loop is pinned in pure/routerControlState.test.js.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";

import { installBrowserShim } from "../helpers/browserShim.js";

const MODULE = "../../../../apps/routerconsole/jsp/js/routerControl.js";

/** The router-control block as ConfigRestartBean renders it, idle. */
const IDLE_BOX = `<!DOCTYPE HTML><html><body><div id=sb_routerControlBox>
<form id=sb_routerControl class="volatile collapse" action="/routerlogs" method=POST>
<input type=hidden name=consoleNonce value=CN-1>
<button type=submit name=action value="restart" class=reload>Restart</button>
<button type=submit name=action value="shutdown" class=stop>Shutdown</button>
</form>
</div></body></html>`;

/** The same block with a shutdown pending, as rendered after a restart. */
const PENDING_BOX = `<!DOCTYPE HTML><html><body><div id=sb_routerControlBox>
<h4 id=sb_shutdownStatus class=volatile><span>Deferring restart for 9m 58s</span></h4>
<form id=sb_routerControl class="volatile collapse" action="/routerlogs" method=POST>
<input type=hidden name=consoleNonce value=CN-2>
<button type=submit name=action value="cancelShutdown" class=cancel>Cancel Restart</button>
</form>
</div></body></html>`;

/**
 * One recorded XMLHttpRequest, completed by the test that started it.
 */
class FakeXHR {
  /** Every instance built in this process, in order. */
  static sent = [];

  open(method, url) {this.method = method;this.url = url;this.status = 0;this.responseText = "";this.headers = {};}
  setRequestHeader(name, value) {this.headers[name] = value;}
  send(body) {this.body = body === undefined ? null : body;FakeXHR.sent.push(this);}

  /**
   * Completes the request as the browser would.
   *
   * @param {number} status - HTTP status to report
   * @param {string} body - responseText to report
   * @returns {void}
   */
  complete(status, body) {
    this.status = status;
    this.responseText = body;
    this.onload();
  }

  /** Fails the request as a network error does. @returns {void} */
  fail() {this.onerror();}
}

/**
 * linkedom has no value property for a button, so the shim's buttons carry the
 * attribute only. The module reads the property, as a browser's does, so the
 * test pages give it one. This is scaffolding for the shim, not a contract.
 *
 * @param {Element} root - the subtree whose buttons are made readable
 * @returns {void}
 */
function withButtonValues(root) {
  for (const button of root.querySelectorAll("button[name=action]")) {
    button.value = button.getAttribute("value");
  }
}

/**
 * Installs the shim, seeds the page with the idle block, stubs the network and
 * the reload, and loads a fresh copy of the module.
 *
 * @returns {Promise<{box: Element, form: Element, reloads: {count: number},
 *                    timers: Function[], start: number}>} the page under test, a
 *                    reload counter, the recorded timers, and the index to read
 *                    this case's requests from
 */
async function load() {
  installBrowserShim();
  location.pathname = "/routerlogs";
  document.documentElement.innerHTML = parseHTML(IDLE_BOX).document.body.innerHTML;
  globalThis.XMLHttpRequest = FakeXHR;
  // The shim records timers and never fires them; the tests fire them by hand.
  const timers = [];
  globalThis.setTimeout = fn => {timers.push(fn);return timers.length;};
  const box = document.getElementById("sb_routerControlBox");
  withButtonValues(box);
  const reloads = {count: 0};
  location.reload = () => {reloads.count += 1;};
  await import(`${MODULE}?case=${Math.random()}`);
  return {
    box,
    form: document.getElementById("sb_routerControl"),
    reloads,
    timers,
    start: FakeXHR.sent.length
  };
}

/**
 * Runs the timers recorded so far, once each, as the browser firing them would.
 *
 * @param {Function[]} timers - the array returned by load()
 * @returns {void}
 */
function fire(timers) {
  for (const timer of timers.splice(0)) {timer();}
}

/**
 * Clicks an action button, then dispatches a bubbling submit on its form, as a
 * browser does. The click is what the module reads the action from in a browser
 * with no submitter on the submit event, which is what the shim is.
 *
 * @param {Element} button - the action button to use
 * @returns {Event} the submit event, so a case can read defaultPrevented
 */
function useButton(button) {
  button.dispatchEvent(new window.Event("click", {bubbles: true, cancelable: true}));
  return submit(button.closest("form"));
}

/**
 * Dispatches a bubbling submit on a form, as a click on one of its buttons does.
 *
 * @param {Element} form - the form to submit
 * @param {?Element} submitter - the button that submitted it, if the browser reports one
 * @returns {Event} the submit event, so a case can read defaultPrevented
 */
function submit(form, submitter = null) {
  const event = new window.Event("submit", {bubbles: true, cancelable: true});
  if (submitter) {
    try {event.submitter = submitter;} catch { /* the shim's Event has no submitter */ }
  }
  form.dispatchEvent(event);
  return event;
}

/**
 * The requests started since a baseline.
 *
 * @param {number} start - index returned by load()
 * @returns {FakeXHR[]} the requests this case caused
 */
function traffic(start) {
  return FakeXHR.sent.slice(start);
}

/**
 * The fields of a request body, as the server reads them.
 *
 * @param {FakeXHR} request - the request to read
 * @returns {Map<string, string>} the form fields it carries
 */
function fields(request) {
  const out = new Map();
  for (const pair of (request.body || "").split("&")) {
    if (!pair) {continue;}
    const at = pair.indexOf("=");
    out.set(decodeURIComponent(pair.slice(0, at)), decodeURIComponent(pair.slice(at + 1)));
  }
  return out;
}

test("the module adds no frame to the page", async () => {
  await load();
  // A frame as the submission target was the first shape of this. Chrome injects
  // its own per-frame scripts into a frame that runs none, and logged a blocked
  // script for every submission; a request has no document to inject into.
  assert.equal(document.querySelectorAll("iframe").length, 0);
});

test("a submission is taken over, so the page is never navigated", async () => {
  const {form, reloads} = await load();
  const event = useButton(form.querySelector('button[name=action]'));
  assert.ok(event.defaultPrevented, "the browser's own submission is cancelled");
  assert.equal(reloads.count, 0, "taking the submission over does not reload the page");
});

test("the form is left as the server rendered it", async () => {
  const {form} = await load();
  useButton(form.querySelector('button[name=action]'));
  // A browser that never runs this module posts to the page it was rendered on,
  // at the top level, as it always has.
  assert.equal(form.getAttribute("action"), "/routerlogs");
  assert.equal(form.getAttribute("target"), null);
});

test("the action is run at the block's own endpoint, with the form's fields", async () => {
  const {form, start} = await load();
  useButton(form.querySelector('button[name=action]'));
  const requests = traffic(start);
  assert.ok(requests.length >= 1, "the submission is sent");
  // Every module copy imported in this process answers the same submit, so the
  // burst is read from the first, which is the one that saw the click.
  const request = requests[0];
  assert.equal(request.method, "POST");
  assert.equal(request.url, "/xhrcontrol.jsp?requestURI=%2Frouterlogs");
  assert.equal(request.headers["Content-Type"], "application/x-www-form-urlencoded; charset=UTF-8");
  assert.equal(fields(request).get("action"), "restart", "the action the server runs");
  assert.equal(fields(request).get("consoleNonce"), "CN-1", "and the nonce guarding it");
});

test("the action sent is the button that was used", async () => {
  const {form, start} = await load();
  const buttons = form.querySelectorAll("button[name=action]");
  useButton(buttons[1]);
  assert.equal(fields(traffic(start)[0]).get("action"), "shutdown");
});

test("the response is the block, and the page is patched from it", async () => {
  const {box, form, reloads, start} = await load();
  useButton(form.querySelector('button[name=action]'));
  const burst = traffic(start).length;
  assert.ok(burst >= 1, "the submission is sent on submit");
  traffic(start)[0].complete(200, PENDING_BOX);
  assert.ok(box.innerHTML.includes("sb_shutdownStatus"), "the countdown is now on the page");
  assert.ok(box.innerHTML.includes("CN-2"), "and it carries the fresh nonce the server issued");
  assert.equal(reloads.count, 0, "a settled block does not reload the page");
  assert.equal(traffic(start).length, burst, "a settled block stops the watching");
});

test("the watching starts at the submit, while the submission is still in flight", async () => {
  const {box, form, reloads, start, timers} = await load();
  useButton(form.querySelector('button[name=action]'));
  const post = traffic(start)[0];
  assert.equal(post.method, "POST", "the action is run by a request");
  assert.ok(timers.length >= 1, "a look at the block is scheduled at the submit");
  fire(timers);
  const looks = traffic(start).slice(1);
  assert.ok(looks.length >= 1, "the block is looked at before the submission answers");
  // Every module copy imported in this process answers the same submit, so the
  // look is found by method, not by position.
  const look = looks.find(r => r.method === "GET");
  assert.ok(look, "the look is a fetch of the block, not a second action");
  look.complete(200, PENDING_BOX);
  assert.ok(box.innerHTML.includes("sb_shutdownStatus"),
            "the countdown lands while the request that runs the action is open");
  assert.equal(reloads.count, 0);
});

test("a block the page cannot show reloads the page", async () => {
  const {form, reloads, start} = await load();
  const box = document.getElementById("sb_routerControlBox");
  // A cosmetic filter hiding by class leaves the element with no layout boxes.
  box.getClientRects = () => [];
  useButton(form.querySelector('button[name=action]'));
  traffic(start)[0].complete(200, PENDING_BOX);
  assert.equal(reloads.count, 1, "a countdown nobody can see is not a delivered control");
});

test("a response without the block reloads the page", async () => {
  const {form, reloads, start} = await load();
  useButton(form.querySelector('button[name=action]'));
  traffic(start)[0].complete(200, "<html><body><p>no block here</p></body></html>");
  assert.equal(reloads.count, 1);
});

test("a submission the server refuses reloads the page", async () => {
  const {form, reloads, start} = await load();
  useButton(form.querySelector('button[name=action]'));
  traffic(start)[0].complete(500, "Server Error");
  assert.equal(reloads.count, 1, "a failed submission is not left silent");
});

test("a network failure reloads the page", async () => {
  const {form, reloads, start} = await load();
  useButton(form.querySelector('button[name=action]'));
  traffic(start)[0].fail();
  assert.equal(reloads.count, 1);
});

test("a countdown that has not arrived is looked at again, not reloaded", async () => {
  const {box, form, reloads, start, timers} = await load();
  useButton(form.querySelector('button[name=action]'));
  const burst = traffic(start).length;
  // The server answered with the block as it stands, before the deferral was
  // published. The block does not reflect the restart, so the watching continues.
  traffic(start)[0].complete(200, IDLE_BOX);
  assert.equal(reloads.count, 0, "a block that has not caught up is not a failure");
  assert.equal(traffic(start).length, burst, "the next look is on a timer, not a burst");
  fire(timers);
  assert.ok(traffic(start).length > burst, "the loop looks at the block again");
  const looks = traffic(start);
  looks[looks.length - 1].complete(200, PENDING_BOX);
  assert.ok(box.innerHTML.includes("sb_shutdownStatus"), "and the countdown lands");
  assert.equal(reloads.count, 0);
});

test("a response from a superseded submission does not touch the page", async () => {
  const {box, form, reloads, start} = await load();
  // Restart, then replace it with a shutdown before the restart's response
  // arrives. The late response describes a state the page has moved past.
  useButton(form.querySelectorAll("button[name=action]")[0]);
  const restartRequest = traffic(start)[0];
  const shutdownStart = FakeXHR.sent.length;
  useButton(form.querySelectorAll("button[name=action]")[1]);
  restartRequest.complete(200, PENDING_BOX);
  assert.ok(!box.innerHTML.includes("sb_shutdownStatus"), "the stale response is dropped");
  assert.ok(traffic(shutdownStart).length >= 1, "the new submission is watching");
  traffic(shutdownStart)[0].complete(200, PENDING_BOX);
  assert.ok(box.innerHTML.includes("sb_shutdownStatus"), "the current submission's response lands");
  assert.equal(reloads.count, 0);
});

test("a cancel is settled by a block with no countdown", async () => {
  const {box, form, reloads, start} = await load();
  // The cancel button only exists once a restart is pending, so reach the
  // pending block through a restart first.
  useButton(form.querySelector('button[name=action]'));
  traffic(start)[0].complete(200, PENDING_BOX);
  const cancelButton = box.querySelector('button[value="cancelShutdown"]');
  assert.ok(cancelButton, "the pending block offers the cancel button");
  withButtonValues(box);
  const cancelStart = FakeXHR.sent.length;
  useButton(box.querySelector("#sb_routerControl button[name=action]"));
  const burst = traffic(cancelStart).length;
  assert.ok(burst >= 1, "the cancel is sent");
  assert.equal(fields(traffic(cancelStart)[0]).get("action"), "cancelShutdown");
  assert.equal(fields(traffic(cancelStart)[0]).get("consoleNonce"), "CN-2",
               "the cancel uses the nonce the pending block was rendered with");
  traffic(cancelStart)[0].complete(200, IDLE_BOX);
  assert.ok(!box.innerHTML.includes("sb_shutdownStatus"), "the countdown is gone");
  assert.equal(reloads.count, 0);
  assert.equal(traffic(cancelStart).length, burst, "the watching stops once the cancel is visible");
});
