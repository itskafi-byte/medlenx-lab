// Run against a Firestore emulator; see README.md. No production project is used.
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, collection, getDocs, serverTimestamp } from 'firebase/firestore';

let env;
const id = '12345678-1234-4123-8123-123456789abc';
const scan = (name, uid) => ({scanId: id, doctorName: 'Dr Test', doctorSpecialty: 'General',
  territory: 'Test', medicines: [{brand: 'Test', generic: 'Test', confidence: 1}],
  scannedBy: name, createdByUid: uid, timestamp: serverTimestamp()});

before(async () => {
  env = await initializeTestEnvironment({projectId: 'demo-medlenx',
    firestore: {rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8')}});
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, 'users/alice'), {companyId: 'company-a'});
    await setDoc(doc(db, 'users/bob'), {companyId: 'company-b'});
    await setDoc(doc(db, 'companies/company-a/members/alice'), {active: true, displayName: 'Alice'});
    await setDoc(doc(db, 'companies/company-b/members/bob'), {active: true, displayName: 'Bob'});
    await setDoc(doc(db, 'companies/company-a/team_scans/old'), {doctorName: 'Dr Test'});
  });
});

test('members can read their own assignment and team, not another team', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(getDoc(doc(db, 'users/alice')));
  await assertFails(getDoc(doc(db, 'users/bob')));
  await assertSucceeds(getDocs(collection(db, 'companies/company-a/team_scans')));
  await assertFails(getDocs(collection(db, 'companies/company-b/team_scans')));
});
test('members cannot self-assign a company or provision membership', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(setDoc(doc(db, 'users/alice'), {companyId: 'company-b'}));
  await assertFails(setDoc(doc(db, 'companies/company-b/members/alice'), {active: true, displayName: 'Alice'}));
});
test('create is attributed, scoped and append-only', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const own = doc(db, 'companies/company-a/team_scans', id);
  await assertFails(setDoc(doc(db, 'companies/company-b/team_scans', id), scan('Alice', 'alice')));
  await assertFails(setDoc(own, scan('Bob', 'alice')));
  await assertFails(setDoc(own, {...scan('Alice', 'alice'), photo: 'image'}));
  await assertSucceeds(setDoc(own, scan('Alice', 'alice')));
  await assertFails(setDoc(own, scan('Alice', 'alice')));
});
test('revoked membership denies access', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'companies/company-a/members/alice'), {active: false, displayName: 'Alice'});
  });
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(getDocs(collection(db, 'companies/company-a/team_scans')));
  await assertFails(setDoc(doc(db, 'companies/company-a/team_scans', id), scan('Alice', 'alice')));
});

const profile = {company: 'Display company', employeeId: 'E1', fullName: 'Alice',
  designation: 'MPO', division: 'Dhaka', territory: 'Test', portfolio: 'General',
  dailyTarget: 10, monthlyTarget: [{brand: 'Example', target: 4}]};
test('profile merge is self-only and preserves administrator assignment', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(alice, 'users/alice'), {officerProfile: profile}, {merge: true}));
  const snap = await assertSucceeds(getDoc(doc(alice, 'users/alice')));
  if (snap.data().companyId !== 'company-a') throw new Error('companyId changed');
  await assertFails(setDoc(doc(alice, 'users/alice'), {companyId: 'company-b', officerProfile: profile}, {merge: true}));
  await assertFails(setDoc(doc(alice, 'users/bob'), {officerProfile: profile}, {merge: true}));
  await assertFails(setDoc(doc(alice, 'users/alice'), {officerProfile: {...profile, dailyTarget: 0}}, {merge: true}));
  await assertFails(setDoc(doc(alice, 'users/alice'), {admin: true}, {merge: true}));
  const newcomer = env.authenticatedContext('newcomer').firestore();
  await assertSucceeds(setDoc(doc(newcomer, 'users/newcomer'), {officerProfile: profile}, {merge: true}));
  await assertFails(setDoc(doc(env.authenticatedContext('intruder').firestore(), 'users/intruder'),
    {companyId: 'company-a', officerProfile: profile}));
});
