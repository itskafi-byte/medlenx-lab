/**
 * DOM smoke test: boots templates/index.html in jsdom against the live server
 * and exercises the real user flows for the four reported issues.
 *
 * Usage:  node tests/dom_smoke.js            (server must be on :8000)
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
  // strip external CDN scripts; stub what the page needs
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
      // Chart.js stub that records what it was given
      win.__charts = {};
      win.Chart = class {
        constructor(ctx, cfg) {
          const id = ctx && ctx.id ? ctx.id : 'unknown';
          win.__charts[id] = cfg;
          this.cfg = cfg;
        }
        destroy() {}
      };
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
  await sleep(2500); // let initApp() finish its fetches

  console.log('\n=== ISSUE 1: cascading location selects ===');
  const dSel = doc.getElementById('fDistrict');
  ok('global district filter populated', dSel && dSel.options.length > 1,
     dSel ? `${dSel.options.length} options` : 'missing');

  // drive renderVerification with a synthetic payload (no upload needed)
  win.eval(`
    window.lastScannedData = {
      id: 1,
      doctor: { name:'Dr. DOM', bmdc_no:'A-1', qualifications:'MBBS',
                hospital:'H', specialty:'', district:'', upazila:'', territory:'' },
      medicines: [{ brand_name:'Napa', strength:'500 mg', type:'Tablet',
                    company:'Beximco Pharmaceuticals Ltd.', company_verified:true,
                    confidence:0.94, line_number:1, raw_text:'Tab Napa' }]
    };
    renderVerification(window.lastScannedData);
  `);
  await sleep(200);

  const dist = doc.getElementById('verifyDistrict');
  const upa = doc.getElementById('verifyUpazila');
  const terr = doc.getElementById('verifyTerritory');
  const spec = doc.getElementById('verifySpecialty');

  ok('District is a <select>', dist && dist.tagName === 'SELECT', dist && dist.tagName);
  ok('Specialty is a <select>', spec && spec.tagName === 'SELECT', spec && spec.tagName);
  ok('District has all 64 districts', dist && dist.options.length === 65,
     dist ? `${dist.options.length - 1} districts` : 'missing');
  ok('Upazila disabled until district chosen', upa && upa.disabled === true);

  // choose a district -> upazila + territory must repopulate
  dist.value = 'Dhaka';
  dist.dispatchEvent(new win.Event('change'));
  await sleep(100);
  ok('Upazila enabled after district', upa.disabled === false);
  ok('Upazila list is Dhaka-specific',
     [...upa.options].some(o => o.value === 'Savar'),
     [...upa.options].slice(1, 4).map(o => o.value).join(','));
  ok('Territory auto-filled', !!terr.value, terr.value);

  upa.value = 'Savar'; upa.dispatchEvent(new win.Event('change'));
  await sleep(50);
  ok('formData tracks upazila', win.eval('formData.location.upazila') === 'Savar');

  // switching district must invalidate the stale upazila
  dist.value = "Cox's Bazar";
  dist.dispatchEvent(new win.Event('change'));
  await sleep(100);
  ok('changing district clears stale upazila', win.eval('formData.location.upazila') === '');
  ok('upazila options swapped to new district',
     [...upa.options].some(o => o.value === 'Teknaf'));
  ok('stale Savar no longer selectable',
     ![...upa.options].some(o => o.value === 'Savar'));

  console.log('\n=== ISSUE 2: save feedback ===');
  ok('toast() defined', win.eval('typeof toast') === 'function');
  win.eval("toast.success('hello')");
  await sleep(50);
  let toastEl = doc.querySelector('#toastRoot [data-toast-id]');
  ok('toast renders in DOM', !!toastEl, toastEl && toastEl.textContent.trim());
  ok('toast shows message', toastEl && toastEl.textContent.includes('hello'));

  // loading -> success reuses the same node
  win.eval("toast.loading('working',{id:'x1'})");
  await sleep(30);
  const n1 = doc.querySelectorAll('#toastRoot [data-toast-id]').length;
  win.eval("toast.success('done',{id:'x1'})");
  await sleep(30);
  const n2 = doc.querySelectorAll('#toastRoot [data-toast-id]').length;
  ok('loading toast upgrades in place (no stacking)', n1 === n2, `${n1} -> ${n2}`);

  ok('no alert() on the save path',
     !/alert\(/.test(win.eval('verifyAndSave.toString()')));
  ok('resetWorkspaceState defined', win.eval('typeof resetWorkspaceState') === 'function');

  console.log('\n=== ISSUE 3: recent scans grid ===');
  await win.eval('loadRecentMedicines()');
  await sleep(400);
  const rows = doc.querySelectorAll('#rsBody tr');
  ok('grid renders itemized rows', rows.length > 0, `${rows.length} rows`);
  if (rows.length) {
    const cells = rows[0].querySelectorAll('td');
    ok('row has 7 columns', cells.length === 7, `${cells.length}`);
    ok('row shows a medicine name', cells[3].textContent.trim().length > 0,
       cells[3].textContent.trim().split('\n')[0]);
    ok('row shows a company', cells[4].textContent.trim().length > 0,
       cells[4].textContent.trim());
    ok('company swatch has a colour',
       /background:\s*(#|hsl)/.test(rows[0].innerHTML));
  }
  const pager = doc.getElementById('rsPager');
  ok('pager rendered', pager && pager.textContent.includes('of'),
     pager && pager.textContent.trim().slice(0, 40));

  console.log('\n=== ISSUE 4: chart colours ===');
  await win.eval('loadCharts()');
  await sleep(800);
  const donut = win.__charts['chartCompanyShareReal'];
  ok('donut chart built', !!donut);
  if (donut) {
    const colors = donut.data.datasets[0].backgroundColor;
    const uniq = new Set(colors);
    ok('every company has its own colour', uniq.size === colors.length,
       `${uniq.size} unique / ${colors.length} slices`);
    ok('no grey #e5e7eb fallback', !colors.includes('#e5e7eb'));
    ok('donut is clickable (drill-down)', typeof donut.options.onClick === 'function');
    console.log('        colours: ' + colors.join(', '));
  }
  const bar = win.__charts['chartMostPrescribedReal'];
  ok('bar chart clickable for doctor drill-down',
     bar && typeof bar.options.onClick === 'function');

  // guard against the '&' vs '?' URL bug: every dashboard fetch must be valid
  const badUrls = [];
  const origFetch = win.fetch;
  win.fetch = (u, o) => {
    const s = String(u);
    if (/\/api\/[^?]*&/.test(s)) badUrls.push(s);
    return origFetch(u, o);
  };
  await win.eval('loadCharts()');
  await win.eval('loadKpis()');
  await win.eval('loadRecentMedicines()');
  await sleep(600);
  ok('no malformed API URLs (& before ?)', badUrls.length === 0,
     badUrls.slice(0, 2).join(' | '));

  console.log('\n=== design: light only ===');
  ok('html locked to light', doc.documentElement.className.includes('light'));
  ok('no theme toggle in DOM', !doc.getElementById('themeToggle'));
  ok('no dark: classes', !/\bdark:/.test(doc.body.innerHTML));

  console.log('\n=== runtime errors ===');
  // Ignore artifacts of this harness: we strip the Tailwind/Chart.js CDN tags
  // and jsdom has no serviceWorker. Neither occurs in a real browser.
  const HARNESS_NOISE = /Not implemented|css|stylesheet|tailwind is not defined|reading 'register'|serviceWorker/i;
  const real = errors.filter(e => !HARNESS_NOISE.test(e));
  ok('no uncaught JS errors', real.length === 0, real.slice(0, 3).join(' | '));

  console.log(`\n${'='.repeat(60)}`);
  console.log(`DOM SMOKE: ${pass} passed, ${fail} failed`);
  console.log('='.repeat(60));
  process.exit(fail ? 1 : 0);
})();
