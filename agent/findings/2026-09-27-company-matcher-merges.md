# The company matcher merges different companies, and counts their rows as own — 2026-09-27

**Where:** `app/medicine_matcher.py:318-340` — `company_key()` / `same_company()`, and its
faithful port `MedicineMatcher.companyKey` / `sameCompany` in this app.
**Status:** **fixed in Android** (the user chose to diverge); **still open in the web**.
Android reproduces the web rule exactly as ported, which is why it needed a decision to
change: the fix makes it stricter than `origin/main`, so a web-side fix would have to land
for the two apps to agree again.
**Found while:** sweeping for defects after the charts-and-cards round, checking what the
new own-company fallback resolves to.

---

## The bug

Both implementations derive a company key by stripping corporate noise

    ltd limited plc inc co company pharmaceuticals? pharma laboratories labs?
    industries healthcare "health care" bd bangladesh

and then join **every surviving token**. Two keys compare equal when they are equal, or
when either is a character prefix of the other:

```python
return ka == kb or ka.startswith(kb) or kb.startswith(ka)
```

The prefix clause is what merges distinct companies. Verified by running the web's own
`same_company` (`app/medicine_matcher.py`, extracted from `origin/main`) against the real
25,105-row catalogue: **14 pairs**, including

| key | company A | company B | rows |
|---|---|---|---|
| `square` / `square toiletries` | Square Pharmaceuticals PLC | Square Toiletries Limited | 942 |
| `sun` / `sunman birdem` | Sun Pharmaceutical Industries Ltd. | Sunman-Birdem Pharma Ltd. | 219 |
| `globe` / `globex` | Globe Pharmaceuticals Ltd. | Globex Pharmaceuticals Ltd. | 333 |
| `leo` / `leon` | Leo Pharmaceuticals Ltd. | Leon Pharmaceuticals Ltd. | 102 |
| `bristol myers squibb` / `bristol` | Bristol Myers Squibb | Bristol Pharmaceuticals Ltd. | 80 |
| `opsonin` / `opsonin herbal amp nutraceuticals` | Opsonin Pharma Ltd. | Opsonin Herbal & Nutraceuticals | 1086 |
| `orion` / `orion infusion`, `radiant` / `radiant nutraceuticals`, `save` / `save trading international`, `albion` / `albion specialized`, `nipro` / `nipro jmi`, `sun` (second pair), `the ibn sina` / `the ibn sina natural medicine`, `radiant` / `radiant export import enterprise` | | | |

Carried along with it: `same_company` reports `True` for
("Square Pharmaceuticals PLC", "Square Toiletries Limited") — a pharmaceutical company and
a toiletries company are one company as far as the audit is concerned.

## What it costs

Every own-vs-competitor decision in the app goes through this function:
`RxAudit.buildMarketShare` (the drawer footer), `AnalyticsMetrics`' own-item counts,
`ScanRepository`'s saved `is_own`, the drawer/audit row badges, and — since the freeze fix
— `MedexIndex.ownCandidates`, which is what selects the candidate set for a substitution
pick. A rep at Square counts Square Toiletries rows as their own portfolio share; a rep at
Globe counts Globex's.

The three merges the rule is *for* are real and must survive any fix: "Square
Pharmaceuticals Ltd." ≡ "Square Pharmaceuticals PLC" (`square`/`square`), "DBL Healthcare
Ltd." ≡ "DBL Pharmaceuticals Ltd." (`dbl`/`dbl`), "Incepta Pharmaceuticals Ltd." ≡
"Incepta Pharmaceuticals PLC" (`incepta`/`incepta`).

## A second, smaller defect underneath

`company_key`'s noise-only fallback keeps the whole normalised name *including* the noise
it just stripped. "Healthcare Pharmaceuticals Ltd." — all three tokens are noise — gets
the key `healthcare pharmaceuticals ltd`, while "Healthcare Pharmaceuticals" gets
`healthcare pharmaceuticals`. Those two are therefore not equal, and only the prefix
clause saves them. Any fix that drops the prefix clause has to fix this first, by trimming
the corporate suffix from the fallback key as well.

## The fix, as applied in Android

Two changes in `MedicineMatcher`:

  * `sameCompany` is `ka == kb`, with the prefix clause gone — two names are no longer the
    same because one spells the other's start;
  * `companyKey`'s noise-only fallback trims trailing corporate-suffix words
    (`ltd|limited|plc|inc|co|company|bd|bangladesh`) but never its last remaining word, so
    "Healthcare Pharmaceuticals Ltd." and "Healthcare Pharmaceuticals" still resolve to
    one key — which is the case the prefix clause was quietly covering.

Measured over the shipped 276-name catalogue: the four intended merges survive, all 14
false pairs separate, **one** name's key changes (the Healthcare fallback above), no new
merges appear, and `MedexIndex.ownCandidates` narrows with it — it used to hand
`findOwnBrand` the same prefix-neighbours, so a rep at Globe could be pitched a Globex
product as their own brand.

An equivalent web-side fix is the same two edits in `company_key` / `same_company`.

## A probe that lied first

The first analysis pass reported **54** merges, including
("S.N. Pharmaceutical Ltd.", "Square Pharmaceuticals PLC") — because the analysis used
the *first token* as the key, not the whole noise-stripped name. That is not the rule
either implementation uses. The real figure is the 14 above, and the alarm it raised about
"Health Pro International" merging with the Healthcare fallback was false: the real keys
are `health pro international` and `healthcare pharmaceuticals ltd`, which are apart. The
fallback literal is safe to use.

The lesson repeats one already in STATE.md: a mirror of a rule that was not read closely
enough is a probe defect, and it looks exactly like an app defect until the real
implementation is run.
