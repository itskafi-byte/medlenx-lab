/**
 * Rx Audit Drawer DOM smoke test: boots templates/index.html in jsdom against
 * the live server and exercises the new drawer, filter, export + tracker UI.
 *
 * Usage:  node tests/dom_smoke_drawer.js   (server must be on :8000)
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('/tmp/node_modules/jsdom');

const BASE = process.env.BASE || 'http://localhost:8000';
const TPL = path.join(__dirname, '..', 'templates', 'index.html');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log(`  [PASS] ${name}`); }
  else { fail++; console.log(`  [FAIL] ${name}${extra ? ' -> ' + extra : ''}`); }
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  let html = fs.readFileSync(TPL, 'utf8');
  html = html.replace(/<script src="https:\/\/[^"]+"><\/script>/g, '');
  html = html.replace(/<script src="\/static\/js\/[^"]+"><\/script>/g, '');

  const vc = new VirtualConsole();
  const errors = [];
  vc.on('jsdomError', e => errors.push(e.message));
  vc.on('error', (...a) => errors.push(a.join(' ')));

  const dom = new JSDOM(html, {
    runScripts: 'dangerously',
    resources: 'usable',
    url: BASE + '/',
    virtualConsole: vc,
    beforeParse(win) {
      win.Chart = class { constructor() {} destroy() {} };
      win.fetch = (...args) => globalThis.fetch(
        typeof args[0] === 'string' && args[0].startsWith('/') ? BASE + args[0] : args[0],
        args[1]
      );
      win.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
      win.scrollTo = () => {};
      win.navigator.serviceWorker = undefined;
      win.requestAnimationFrame = cb => setTimeout(cb, 0);
    },
  });

  const win = dom.window;
  const doc = win.document;
  await new Promise(r => win.addEventListener('load', r));
  await sleep(2500);

  console.log('\n=== Recent Prescriptions cards are clickable ===');
  const cards = doc.querySelectorAll('.rx-card');
  ok('rx-cards rendered from live data', cards.length > 0, `${cards.length} cards`);
  ok('cards carry data-rx-id', cards.length && cards[0].dataset.rxId);

  console.log('\n=== Drawer opens on card click ===');
  cards[0].dispatchEvent(new win.Event('click', { bubbles: true }));
  await sleep(700);
  const drawer = doc.getElementById('rxDrawer');
  ok('drawer visible', drawer && !drawer.classList.contains('hidden'));
  const rxNo = doc.getElementById('rxDrawerRxNo').textContent;
  ok('Rx number filled', /^A-\d+$/.test(rxNo), rxNo);
  const rows = doc.querySelectorAll('#rxAuditItems tr');
  ok('medicine rows rendered', rows.length > 0, `${rows.length} rows`);
  ok('filter pills have counts',
     /All \(\d+\)/.test(doc.querySelector('[data-filter="all"]').textContent),
     doc.querySelector('[data-filter="all"]').textContent);
  ok('market share summary rendered',
     /Market Share Summary/.test(doc.getElementById('rxAuditShare').textContent));
  ok('company badge present in row', doc.querySelector('#rxAuditItems .rx-crop-hover') !== null);

  console.log('\n=== Search + filter interaction ===');
  const search = doc.getElementById('rxAuditSearch');
  search.value = 'zzzznomatch';
  search.dispatchEvent(new win.Event('input', { bubbles: true }));
  await sleep(100);
  ok('empty state appears on no match', !doc.getElementById('rxAuditEmpty').classList.contains('hidden'));
  search.value = '';
  search.dispatchEvent(new win.Event('input', { bubbles: true }));
  await sleep(100);
  const lowPill = doc.querySelector('[data-filter="low"]');
  lowPill.dispatchEvent(new win.Event('click', { bubbles: true }));
  await sleep(100);
  ok('low-confidence filter applied without error', !drawer.classList.contains('hidden'));
  doc.querySelector('[data-filter="all"]').dispatchEvent(new win.Event('click', { bubbles: true }));
  await sleep(100);

  console.log('\n=== Close via Escape ===');
  doc.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  await sleep(400);
  ok('drawer hidden after Escape', drawer.classList.contains('hidden'));

  console.log('\n=== Duplicate Rx tag on duplicate card ===');
  const dupCard = [...doc.querySelectorAll('.rx-card')].find(c => c.innerHTML.includes('Duplicate Rx'));
  ok('duplicate card shows red tag (if dupes exist)', dupCard !== undefined || cards.length < 2,
     dupCard ? 'found' : 'no dupes in dataset');

  console.log('\n=== Doctor target tracker present ===');
  ok('tracker form exists', doc.getElementById('doctorTargetForm') !== null);
  ok('tracker table exists', doc.getElementById('dtBody') !== null);

  if (errors.length) {
    console.log('\nJS errors during run:');
    errors.slice(0, 5).forEach(e => console.log('  !', e));
  }
  ok('no page JS errors', errors.length === 0, errors.slice(0, 3).join(' | '));

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('SMOKE CRASH', e); process.exit(1); });
