import fs from 'node:fs';
import vm from 'node:vm';

const script = fs.readFileSync(process.argv[2], 'utf8');
const catalog = JSON.parse(process.argv[3]);

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function createSelect(values, value, autoSelectFirst = false) {
  const select = {
    id: 'model_deepseek_select',
    options: values.map((entry) => ({ value: entry, text: entry })),
    assignments: 0,
    autoSelectFirst,
    _value: value,
    appendChild(option) {
      const wasEmpty = this.options.length === 0;
      this.options.push(option);
      if (this.autoSelectFirst && wasEmpty) this._value = option.value;
    },
  };
  Object.defineProperty(select, 'value', {
    get() { return this._value; },
    set(next) {
      this._value = next;
      this.assignments += 1;
    },
  });
  return select;
}

function run(select) {
  const sandbox = {
    console,
    currentSelect: select,
    listeners: [],
    observers: [],
    timers: [],
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.setTimeout = (callback) => {
    sandbox.timers.push(callback);
    return sandbox.timers.length;
  };
  sandbox.document = {
    getElementById(id) {
      return id === 'model_deepseek_select' ? sandbox.currentSelect : null;
    },
    createElement(tag) {
      if (tag !== 'option') throw new Error(`unexpected element ${tag}`);
      return { value: '', text: '' };
    },
    addEventListener(type, callback, capture) {
      sandbox.listeners.push({ type, callback, capture });
    },
  };
  sandbox.MutationObserver = class {
    constructor(callback) {
      this.callback = callback;
      sandbox.observers.push(this);
    }
    observe(target, options) {
      this.target = target;
      this.options = options;
    }
    disconnect() {
      this.disconnected = true;
    }
  };
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  return sandbox;
}

function valuesOf(select) {
  return select.options.map((option) => option.value);
}

const initial = createSelect([''], '');
const installed = run(initial);
assert(valuesOf(initial).join(',') === ['', ...catalog].join(','), `initial options: ${valuesOf(initial)}`);
assert(initial.value === '', 'placeholder selection changed');
assert(initial.assignments === 0, 'script rewrote an unchanged selection');
assert(installed.observers.length === 1, 'select was not observed');
assert(installed.observers[0].options.childList === true, 'observer is not watching option changes');
assert(installed.listeners.some((listener) => listener.type === 'pointerdown' && listener.capture === true), 'pointerdown hook missing');

installed.currentSelect = null;
vm.runInContext(script, installed);
assert(installed.observers.length === 1, 'second injection installed another observer');

initial.options = [
  { value: 'deepseek-flash', text: 'deepseek-flash' },
  { value: 'custom-router', text: 'custom-router' },
];
initial._value = 'deepseek-flash';
initial.assignments = 0;
installed.observers[0].callback();
assert(initial.value === 'deepseek-flash', `refresh changed selection to ${initial.value}`);
assert(initial.assignments === 0, 'refresh rewrote the selected model');
for (const id of catalog) {
  assert(valuesOf(initial).filter((value) => value === id).length === 1, `missing or duplicated ${id}`);
}
assert(valuesOf(initial).includes('custom-router'), 'API-only model was removed');

initial.options = [];
initial._value = '';
initial.assignments = 0;
initial.autoSelectFirst = true;
installed.observers[0].callback();
assert(initial.value === '', `empty select was left on ${initial.value}`);
assert(valuesOf(initial).join(',') === catalog.join(','), `cleared options: ${valuesOf(initial)}`);

const late = run(null);
assert(late.timers.length === 1, 'missing select did not schedule a retry');
assert(late.observers.length === 0, 'missing select installed an observer');
late.currentSelect = createSelect([''], '');
late.timers[0]();
assert(valuesOf(late.currentSelect).join(',') === ['', ...catalog].join(','), 'retry did not fill the select');

const replaced = createSelect([''], '');
const pointer = installed.listeners.find((listener) => listener.type === 'pointerdown');
pointer.callback({ target: replaced });
assert(valuesOf(replaced).join(',') === ['', ...catalog].join(','), 'pointerdown did not fill a replaced select');
assert(installed.observers[0].disconnected === true, 'old observer was kept after the select node changed');
assert(installed.observers[1].target === replaced, 'new select was not observed');

console.log('deepseek model options ok');
