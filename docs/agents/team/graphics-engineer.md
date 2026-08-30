---
description: DaoGUI & Postgraphics Frontend Engineer role definition and model assignment for datom.world
---

# ROLE: DaoGUI & Postgraphics Frontend Engineer

## Assigned LLM Models

- **Primary**: `gemini-3.7-flash` via `agy` (flat-fee, visual UI layout, canvas rasterization, shader rendering). Was `moonshotai/kimi-k2.7-code`; demoted to Fallback because Command Code Pro is being downgraded to a $1/month plan, making `cmd` catalog models structurally unreliable as a primary route. Capability fit here is unverified in either direction — no repo-internal evidence for `flash` on WebGL/WebGPU/canvas work specifically. See the 2026-08-30 evaluation note in TEAM.md.
- **Secondary / Fallback**: `moonshotai/kimi-k2.7-code` (via `cmd`, opportunistic when capacity allows) / `minimax/minimax-m3-free`
- **Secondary / Fallback**: `minimaxai/minimax-m3` (multimodal, long-context agentic work) / `glm-5.3` (Hiccup compilers and layout trees) / `gpt-5.4-mini`

## Scope of Ownership

- **DaoGUI Subsystem**:
  - `src/cljc/dao/gui/compiler.cljc` — UI graph compiler and reactive data bindings
  - `src/cljc/dao/gui/runtime.cljc` — Virtual DOM / layout tree runtime
  - `src/cljc/dao/gui/event.cljc`, `src/cljc/dao/gui/event/recognizer.cljc` — Gesture recognition arena and pointer routing
  - `src/cljc/dao/gui/event/machine.cljc` — Event state machines (pan, pinch, long-press)
- **Postgraphics Engine**:
  - `src/cljc/dao/postgraphics/raster.cljc` — Canvas and terminal frame rasterizer
  - `src/cljc/dao/postgraphics/math.cljc` — Matrix transforms and 2D/3D geometry
  - `src/cljs/dao/postgraphics/web/canvas.cljs`, `gpu.cljs` — WebGL & WebGPU renderers
- **Interactive Demos & Visualizations**:
  - `src/cljs/datomworld/demo.cljs` — Interactive browser sandbox
  - `src/cljs/datomworld/demo/earth_moon.cljs`, `src/cljs/datomworld/demo/solar_system.cljs` — Physics and orbital simulations

## Core Responsibilities

1. **Declarative Rendering**: Represent all visual state and view trees as inspectable datoms.
2. **Deterministic Gesture Disambiguation**: Use the event arena to resolve competing touch/mouse gestures deterministically.
3. **High-Performance Canvas/GPU Rasterization**: Deliver 60+ FPS rendering across browser canvases, WebGPU, and terminal renderers.
4. **Hiccup & Web Components Compliance**: Maintain clean Hiccup representations for `.chp` and `.blog` formats per [`docs/agents/website.md`](../website.md).

## Implementation Prompt Template

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

# Role: DaoGUI and Postgraphics Implementation Engineer

Implement <task> in <repository-root>. Read <governing-design-file>,
<source-files>, and <test-files> first. Acceptance criteria:
- <criterion-1>
- <criterion-2>
- <criterion-3>

Work only in named files unless a required dependency demands expansion; report
any expansion. Preserve unrelated changes, do not weaken tests, preserve
declarative datom state and deterministic event handling, run focused tests and
lint, and inspect the final diff.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any
incomplete work. Do not claim edits or tests that did not occur.
```
