Created-GMT: 2026-09-20 08:42:00 GMT
Created-Local: 2026-09-20 15:42:00 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your consensus thread)
# Task: mob closing round — respond to fable's Q1 dispute

Fable-5-1 disputes your Q1 ruling. Its argument:

- `dao.lease.md:154-157`: the general `:silence` clause is "its interval
  since the last relevant observation exceeds duration plus tolerance".
  The unknown sentence — "due for `:silence` **only once** a full duration
  has passed since its resumed reading" — states a NECESSARY condition,
  not a replacement for the general clause.
- Read as a conjunction: an unknown lease is due only when BOTH the
  interval since the last relevant observation exceeds duration +
  tolerance AND a full duration has passed since the resumed reading.
- Your sufficient-only reading makes a gap SPEED UP the reclaim: duration
  10, tolerance 5, last observation 100 — no gap, due at 115; gap at 101,
  due at 111. That contradicts `dao.lease.md:176`: "Absence is evidence
  only over a window the judge observed. Weaker evidence may delay a
  conclusion of silence. It must not speed one up."
- Its preferred contract wording: "A lease whose evidence state is
  `unknown` is due for `:silence` only once, **in addition**, a full
  duration has passed since its resumed reading."
- Code consequence: the unknown branch of `classify-entry` must also
  require the known-branch condition, plus a gap-inside-tolerance test.

Its Q2 and Q3 positions: agrees with your rulings; wants the Q2 cap basis
written into the contract ("measured from its observed grant" + a Limits
line) and accepts your Q3 sentence with one corrected premise (a
serving-path nudge IS legitimate — the plan merely wires none).

Respond to the Q1 dispute: concede the conjunction reading or defend the
sufficient-only reading. If you concede, confirm the contract wording and
the code consequence. If you hold, argue from the text. Also confirm (or
amend) fable's Q2 wording additions and the Q3 sentence amendment.

Read-only. End with exactly:
`CONSENSUS: <Q1 ruling | Q2 ruling | Q3 ruling>`
