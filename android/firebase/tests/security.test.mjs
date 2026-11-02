// Run against a Firestore emulator; see README.md. No production project is used.
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, collection, getDocs, query, where, serverTimestamp, Timestamp, writeBatch } from 'firebase/firestore';

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
    await setDoc(doc(db, 'users/charlie'), {companyId: 'company-a'});
    await setDoc(doc(db, 'companies/company-a/members/alice'), {active: true, displayName: 'Alice'});
    await setDoc(doc(db, 'companies/company-b/members/bob'), {active: true, displayName: 'Bob'});
    await setDoc(doc(db, 'companies/company-a/members/charlie'), {active: true, displayName: 'Charlie'});
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

test('own history query uses the stored creator field and company boundary', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(alice, 'companies/company-a/team_scans', id), scan('Alice', 'alice')));
  const own = query(collection(alice, 'companies/company-a/team_scans'), where('createdByUid', '==', 'alice'));
  const snapshot = await assertSucceeds(getDocs(own));
  if (snapshot.size !== 1 || snapshot.docs[0].id !== id) throw new Error('own history query failed');
  await assertFails(getDocs(query(collection(alice, 'companies/company-b/team_scans'), where('createdByUid', '==', 'alice'))));
});

test('capture time is optional for legacy scans and type-checked separately from server upload time', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const own = doc(db, 'companies/company-a/team_scans', id);
  const capture = Timestamp.fromMillis(Date.UTC(2025, 0, 2, 8, 30));
  await assertFails(setDoc(own, {...scan('Alice', 'alice'), captureTimestamp: 'not a timestamp'}));
  await assertSucceeds(setDoc(own, {...scan('Alice', 'alice'), captureTimestamp: capture}));
  const saved = await assertSucceeds(getDoc(own));
  if (saved.data().captureTimestamp.toMillis() !== capture.toMillis()) throw new Error('capture time was not preserved');
  if (!(saved.data().timestamp instanceof Timestamp)) throw new Error('upload time is missing');
  const oldId = '12345678-1234-4123-8123-123456789abd';
  await assertSucceeds(setDoc(doc(db, 'companies/company-a/team_scans', oldId), {...scan('Alice', 'alice'), scanId: oldId}));
});

test('avatar IDs are self-owned, restricted and shared only within the active company', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const charlie = env.authenticatedContext('charlie').firestore();
  const own = doc(alice, 'users/alice');
  const directory = doc(alice, 'companies/company-a/avatars/alice');
  await assertFails(setDoc(own, {avatarId: 'https://example.com/tracking'}, {merge: true}));
  await assertFails(setDoc(own, {avatarId: 'avatar_09'}, {merge: true}));
  await assertSucceeds(setDoc(own, {avatarId: 'avatar_02'}, {merge: true}));
  await assertFails(setDoc(directory, {avatarId: 'avatar_01'}));
  await assertFails(setDoc(directory, {avatarId: 'avatar_02', name: 'private'}));
  await assertSucceeds(setDoc(directory, {avatarId: 'avatar_02'}));
  await assertFails(setDoc(doc(bob, 'companies/company-a/avatars/alice'), {avatarId: 'avatar_02'}));
  await assertFails(getDoc(doc(bob, 'users/alice')));
  await assertFails(getDocs(collection(bob, 'companies/company-a/avatars')));
  await assertSucceeds(getDocs(collection(alice, 'companies/company-a/avatars')));
  await assertSucceeds(getDocs(collection(charlie, 'companies/company-a/avatars')));
  await assertFails(getDoc(doc(charlie, 'users/alice')));
  await assertFails(setDoc(doc(charlie, 'companies/company-a/avatars/alice'), {avatarId: 'avatar_02'}));
  const batch = writeBatch(alice);
  batch.set(own, {avatarId: 'avatar_03'}, {merge: true});
  batch.set(directory, {avatarId: 'avatar_03'});
  await assertSucceeds(batch.commit());
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'companies/company-a/members/alice'), {active: false, displayName: 'Alice'});
  });
  await assertFails(getDocs(collection(alice, 'companies/company-a/avatars')));
});
