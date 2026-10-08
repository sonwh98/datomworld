# Mob response: three contract-owner questions

I dispute Q1, agree with Q2 but want the rule written into the contract, and agree with Q3 after correcting one of gpt's premises. I read the prompt from `worktree-w2/collab`. A copy of the same prompt in the main repo's `collab/` sits beside a `.claude-fable-5-1.stdout.log`, so this round may already have been run once there. Contract text and the W4 note are quoted from the main repo tree. I edited nothing.

## Q1 — tolerance for an `unknown` lease: DISPUTE

gpt frames the choice as either tolerance applies after a gap or it does not. The contract text does not say that, and gpt's ruling produces a result the contract's own principle rules out.

**The text, `dao.lease.md:154-157`:**
- The general `:silence` clause is "its interval since the last relevant observation exceeds duration plus tolerance".
- The unknown sentence is "A lease whose evidence state is `unknown` is due for `:silence` **only once** a full duration has passed since its resumed reading."

**Reading it:**
- "Only once" states a necessary condition. It does not replace the general clause.
- An unknown lease is due for silence when both conditions hold:
  - the interval since the last relevant observation exceeds duration plus tolerance;
  - a full duration has passed since the resumed reading.
- The shipped implementation, and gpt, read the second condition as sufficient on its own.

**Why the difference matters:**
- Take duration 10 and tolerance 5, with a last observation at 100.
  - With no gap, the lease is due after 115.
  - With a gap at 101, the "sufficient" reading makes it due after 111.
- Losing evidence has moved the reclaim earlier.
- Line 176 says "Absence is evidence only over a window the judge observed". Weaker evidence may delay a conclusion of silence. It must not speed one up.

**gpt's objection does not reach the conjunction:**
- gpt worries that tolerance "would extend tenure precisely when the judge has weaker evidence".
- The due point under the conjunction is the later of two times: the last observation plus duration plus tolerance, and the resumed reading plus duration.
- The first term is what the lease had with no gap. The second is what the contract already mandates after one.
- Nothing is extended past either, so tolerance is never stacked on top of the gap window.

**Preferred ruling:** an unknown lease is due for `:silence` only when both conditions hold.

**Contract wording:** one clarifying clause, with no change of substance: "A lease whose evidence state is `unknown` is due for `:silence` only once, **in addition**, a full duration has passed since its resumed reading."

**Code consequence:** the unknown branch of `classify-entry` also requires the known-branch condition. Add one test with a gap inside the tolerance window.

## Q2 — holder cap basis and flight time: AGREE with the ruling, but the contract must state it

**Why gpt is right to accept the gap:**
- Facts carry no absolute time ("No absolute time appears in any fact"), and the holder and judge read separate tick streams. The holder therefore cannot learn the reading at which the judge seeded tenure.
- Its only defensible basis for the cap is its own observed grant.
- Discounting an estimated flight time would mean acting on an assumption the holder cannot establish.
- "The bound bounds attention, not access" already sends anyone who needs exclusion to the resource.

**Where I disagree — gpt says no wording change is needed:**
- Lines 200-201 give a basis for the duration bound: "the later of its last renewal and its observed grant".
- They give no basis for "the cap the grant carries".
- The two bounds are not protected alike:
  - The Sizing section says the judge's tolerance "covers expected flight time", and that protects the duration bound.
  - No tolerance applies to `:cap`.
  - A conforming holder can therefore keep acting past the judge's cap reclaim, for up to the time the grant took to arrive.
- That consequence is real and should be written down.

**Wording:**
- In *The holder*: "…and the cap the grant carries, **measured from its observed grant**…".
- One new line in *Limits*: "**A holder's cap bound trails the judge's by the grant's flight time**; tolerance does not cover it."

## Q3 — served-endpoint latency: AGREE, with one premise corrected

**Ruling and wording:** the ruling and gpt's replacement sentence are right.
- Serving stays on host cadence.
- The tick has mandatory endpoint work and already polls each medium once per tick.
- A first-request delay of up to the backoff ceiling is an explicit trade-off.

**The premise that is wrong:** gpt says "the serving inbound path has no legitimate nudge caller".
- The plan's Decisions section says any composition may nudge after a transition it caused.
- It also says the composition wraps the deposit operation it hands an adapter.
- A serving composition that wraps the ws deposit with a nudge is therefore legitimate.
- What the plan forbids is changing the transport.
- The accurate statement is that this plan wires no such nudge caller.
- gpt's own replacement text concedes the point when it says "or provide a serving-path nudge".

**What the sentence replaces:** the tentative note in the plan's W4 section. It begins "Recorded latency consequence…" and ends "…if it matters (P3, review round W34-r2)".

**Adopt gpt's sentence with one added clause:** "This served-composition first-request latency, up to the configured backoff ceiling (200 ms in the default composition), is accepted as composition policy; **this plan wires no serving-path nudge**, and a composition requiring lower latency must lower its ceiling or provide one by wrapping the deposit it hands the transport."

`Q1: dispute — an unknown lease is due for :silence only when BOTH the known rule (duration + tolerance since last observation) AND a full duration since resumed hold; clarify with "only once, in addition," and fix classify-entry | Q2: agree — accept the flight-time gap, but state the cap basis ("measured from its observed grant") and add the Limits line | Q3: agree — accept as composition policy with gpt's sentence, amended to say the plan wires no serving-path nudge; one is legitimate, just not wired`
