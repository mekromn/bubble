const vm = require('node:vm');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const code = fs.readFileSync('app/src/main/assets/chat-monitor/monitor.js', 'utf8');
function fixture(origin = 'https://chatgpt.com') {
  let clock = 0, serial = 0, observe;
  const timers = new Map(), events = [], vaultEvents = [], handlers = {}, clicks = {}, dispatched = [];
  const state = {answer: null, stop: false, stream: false, actions: false, error: false, text: ''};
  class Element {
    closest(selector) { return selector === 'button' ? this : turn; }
    contains(node) { return node === this; }
    get textContent() { return state.text; }
    get innerText() { return state.text; }
    getAttribute() { return null; }
  }
  const button = new Element();
  const turn = {
    querySelector(selector) { return selector.includes('role="alert"') ? (state.error ? {} : null) : (state.actions ? {} : null); },
    getAttribute(name) { return name === 'data-testid' ? 'conversation-turn-test' : null; }, id: ''
  };
  const document = {documentElement: {}, querySelectorAll() {return state.answer ? [state.answer] : [];},
    querySelector(selector) {return selector.includes('.result-streaming') ? (state.stream ? state.answer : null) : (state.stop ? button : null);},
    addEventListener(type, fn) {clicks[type] = fn;}};
  class CustomEvent { constructor(type) { this.type = type; } }
  const window = {
    addEventListener(type, fn) {handlers[type] = fn;},
    dispatchEvent(event) {dispatched.push(event.type); return true;}
  }; window.top = window;
  const location = {origin, pathname: '/c/test'};
  state.answer = new Element();
  vm.runInNewContext(code, {window, document, location, Element, CustomEvent,
    MutationObserver: class {constructor(fn) {observe = fn;} observe() {} disconnect() {}},
    setTimeout(fn, delay) {const id = ++serial; timers.set(id, {at: clock + delay, fn}); return id;},
    clearTimeout(id) {timers.delete(id);}, crypto: {randomUUID() {return 'run-' + (++serial);}},
    browser: {runtime: {sendNativeMessage(app, message) {
      if (app === 'bubble') {
        assert.deepEqual(Object.keys(message).sort(), ['event', 'run']);
        events.push({...message});
      } else if (app === 'bubbleVault') vaultEvents.push({...message});
      else assert.fail(`Unexpected native app ${app}`);
      return Promise.resolve();
    }}}
  });
  function advance(ms = 1500) {
    const end = clock + ms;
    for (let i = 0; i < 100; i++) {
      const next = [...timers].sort((a,b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > end) break;
      timers.delete(next[0]); clock = next[1].at; next[1].fn();
    }
    clock = end;
  }
  function change(props, mutation = false) {Object.assign(state, props); observe?.(mutation ? [{target: state.answer}] : []); advance();}
  return {state, change, advance, events, vaultEvents, dispatched, handlers, clicks, location, button,
    answer: () => new Element(), types: () => events.map(e => e.event), vaultTypes: () => vaultEvents.map(e => e.event)};
}
let f = fixture(); f.change({actions: true}); assert.deepEqual(f.types(), [], 'Loading history must not alert');
f.change({stop: true, text: 'a'}); f.change({answer: f.answer(), stream: true, text: 'answer grows'});
f.change({stop: false, stream: false, actions: true, text: 'answer complete'}); f.change({});
assert.deepEqual(f.types(), ['started', 'finished']);
assert.equal(f.events[0].run, f.events[1].run);
assert.ok(f.vaultTypes().includes('vault-generation-started'));
assert.ok(f.vaultTypes().includes('vault-generation-progress'), 'Real assistant text output must feed the no-output watchdog');
assert.ok(f.vaultTypes().includes('vault-generation-ended'));
assert.ok(f.dispatched.includes('__bubble_vault_full_sync_request_v1__'), 'Completed replies should request authoritative Vault sync');
f = fixture(); f.change({stop: true}); f.change({}, true); f.change({stop: false, actions: true});
assert.deepEqual(f.types(), ['started','finished'], 'Regenerating in the same node must work');
f = fixture(); f.change({stop: true, stream: true, text: 'partial'}); f.clicks.click({target: f.button}); f.change({stop: false, stream: false, actions: true});
assert.deepEqual(f.types(), ['started','aborted'], 'Stop must never produce a completion sound');
f = fixture(); f.change({stop: true, stream: true, text: 'partial'}); f.change({stop: false, stream: false});
assert.deepEqual(f.types(), ['started'], 'No positive completion marker: stay indeterminate');
f.change({actions: true}); assert.deepEqual(f.types(), ['started','finished']);
f = fixture(); f.change({stop: true, stream: true, text: 'partial'}); f.change({stop: false, stream: false, actions: true, error: true});
assert.deepEqual(f.types(), ['started','aborted']);
f = fixture(); f.change({stop: true}); f.change({stop: false, actions: true});
assert.deepEqual(f.types(), ['started'], 'Old action buttons are not completion evidence');
f = fixture(); f.change({stop: true, stream: true, text: 'partial'}); f.location.pathname = '/c/another'; f.change({stop: false, stream: false, actions: true});
assert.deepEqual(f.types(), ['started','aborted']);
f = fixture(); f.change({stop: true, stream: true, text: 'partial'}); f.change({stop: false, stream: false, actions: true});
f.handlers.pagehide(); f.handlers.pageshow({persisted: true}); f.advance();
assert.deepEqual(f.types(), ['started','finished'], 'BFCache restoration must not replay');
f.change({stop: true, stream: true, text: 'next'}); f.change({stop: false, stream: false});
assert.deepEqual(f.types(), ['started','finished','started','finished'], 'BFCache observer must reconnect');
for (const origin of ['https://chatgpt.com.evil.test','http://chatgpt.com','https://example.com']) {
  f = fixture(origin); f.change({stop: true, stream: true}); assert.deepEqual(f.types(), []);
}
console.log('Reply monitor: history, completion, output-driven watchdog progress, regeneration, cancellation, error, navigation, BFCache, deduplication and origin isolation passed.');
