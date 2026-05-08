# Design: Scene UI

## Summary

This document defines `scene.ui`, the first concrete domain vocabulary built on
top of [scene-algebra.md](scene-algebra.md).

`scene.ui` is scoped to:

- 2D productivity applications
- 2D game UI
- 2D game scenes where layout, text, panels, overlays, and input structure are
  more important than 3D world simulation

It is not a browser DOM and not a generic world-simulation vocabulary.

The intended pipeline is:

```text
hiccup/components/atoms
-> scene.ui fragments
-> assembled scene
-> ui graphics bytecode
-> terminal renderer
```

## Relationship to `scene.core`

`scene.ui` extends `scene.core`.

`scene.core` defines:

- fragment composition
- rooted structure
- transforms
- style shell
- generic container, group, and viewport semantics

`scene.ui` adds UI-domain semantics such as:

- text layout intent
- text input semantics
- scrolling
- focus
- selection
- lists and tables
- buttons and overlays
- accessibility metadata

`scene.ui` must not redefine fragment composition. It uses the algebra from
`scene.core`.

## Scope

`scene.ui` is intended to cover:

- panels
- text labels
- editable text fields
- buttons and toggles
- scroll regions
- lists
- tables
- overlays and popups
- HUD-style 2D game UI
- menus
- inventory grids
- dialog systems

Not in v1:

- DOM compatibility
- CSS compatibility
- browser event model emulation
- 3D world semantics
- VR/XR semantics

## UI Node Kinds

`scene.ui` extends the `scene.core` kinds with these UI-specific kinds in v1:

- `:ui/text-block`
- `:ui/text-input`
- `:ui/button`
- `:ui/toggle`
- `:ui/scroll`
- `:ui/list`
- `:ui/table`
- `:ui/overlay`
- `:ui/icon`
- `:ui/image`

It also reuses these `scene.core` kinds:

- `:scene/container`
- `:scene/text`
- `:scene/rect`
- `:scene/group`
- `:scene/viewport`

## Common UI Fields

Any `scene.ui` node may additionally carry:

- `:ui/id`
  - opaque semantic identifier
- `:ui/enabled`
  - boolean
  - default: `true`
- `:ui/focusable`
  - boolean
  - default: `false`
- `:ui/role`
  - keyword
- `:ui/label`
  - string
- `:ui/state`
  - map

These are semantic fields. They are not terminal widget objects.

## Text Semantics

Text-heavy 2D applications need richer text semantics than `scene.core` alone.

### `:ui/text-block`

Required fields:

- `:node/kind`
- `:text/value`

Optional fields:

- `:text/font-size`
- `:text/font-family`
- `:text/align`
  - one of `:start`, `:center`, `:end`, `:justify`
- `:text/wrap`
  - boolean
  - default: `true`
- `:text/line-height`
  - positive number
- `:text/max-lines`
  - positive integer
- `:text/overflow`
  - one of `:clip`, `:ellipsis`
- `:text/selectable`
  - boolean
  - default: `false`

### `:ui/text-input`

Required fields:

- `:node/kind`
- `:text/value`

Optional fields:

- `:text/placeholder`
  - string
- `:text/cursor`
  - non-negative integer
- `:text/selection`
  - `[start end]`
- `:text/multiline`
  - boolean
  - default: `false`
- `:ui/focusable`
  - default: `true`
- `:ui/enabled`
  - default: `true`

`scene.ui` records text editing semantics as data. It does not define the event
system that mutates them.

## Scroll and Viewport Semantics

### `:ui/scroll`

Required fields:

- `:node/kind`
- `:node/children`
- `:scroll/axis`

` :scroll/axis` must be one of:

- `:x`
- `:y`
- `:both`

Optional fields:

- `:scroll/offset`
  - `[x y]`
  - default: `[0 0]`
- `:scroll/content-size`
  - `[width height]`
- `:scroll/viewport-size`
  - `[width height]`
- `:scroll/show-bars`
  - boolean
  - default: `true`

` :ui/scroll` is semantic scrolling structure, not a backend scroll widget.

### `:ui/overlay`

Required fields:

- `:node/kind`
- `:node/children`

Optional fields:

- `:overlay/anchor`
  - node ID or semantic anchor token
- `:overlay/modal`
  - boolean
  - default: `false`
- `:overlay/z-index`
  - number

## Interactive Controls

### `:ui/button`

Required fields:

- `:node/kind`
- `:node/children`

Optional fields:

- `:ui/enabled`
- `:ui/focusable`
  - default: `true`
- `:ui/state`
  - may carry `:pressed?`, `:hovered?`, `:active?`

### `:ui/toggle`

Required fields:

- `:node/kind`
- `:toggle/value`

Optional fields:

- `:ui/enabled`
- `:ui/focusable`
  - default: `true`
- `:ui/label`

## Collection Layout Nodes

### `:ui/list`

Required fields:

- `:node/kind`
- `:node/children`
- `:list/orientation`

` :list/orientation` must be `:row` or `:column`.

Optional fields:

- `:list/gap`
  - non-negative number
- `:list/virtualized`
  - boolean
  - default: `false`

### `:ui/table`

Required fields:

- `:node/kind`
- `:node/children`
- `:table/columns`

` :table/columns` is a vector of column descriptors in v1.

Optional fields:

- `:table/header?`
  - boolean
  - default: `true`
- `:table/row-gap`
  - non-negative number
- `:table/column-gap`
  - non-negative number

## Visual Asset Nodes

### `:ui/icon`

Required fields:

- `:node/kind`
- `:icon/name`

### `:ui/image`

Required fields:

- `:node/kind`
- `:image/src`

Optional fields:

- `:image/fit`
  - one of `:contain`, `:cover`, `:fill`
- `:image/size`
  - `[width height]`

## Focus and Accessibility

`scene.ui` should preserve semantic metadata needed by later input and
accessibility systems.

V1 semantic fields:

- `:ui/focusable`
- `:ui/role`
- `:ui/label`
- `:ui/enabled`

Not in v1:

- full keyboard event routing
- IME semantics
- screen-reader protocol integration

Those can be layered later, but the scene values should already carry the
semantic hooks.

## Lowering Expectations

Lowering from `scene.ui` to graphics bytecode must:

- preserve child order
- preserve visibility semantics
- respect scroll and viewport clipping boundaries
- lower text blocks into explicit text graphics bytecode
- lower controls into visible geometry and text ops
- preserve overlay ordering

Lowering from `scene.ui` must not:

- emit browser-specific semantics
- require a DOM
- collapse focus or accessibility metadata into terminal-only paint data unless
  a separate interaction pipeline handles it

## Example UI Fragment

```clojure
{:fragment/root :panel
 :fragment/nodes
 {:panel {:node/kind :scene/container
          :layout {:kind :stack
                   :direction :column
                   :gap 12
                   :padding 16}
          :node/children [:title :search :items]}
  :title {:node/kind :ui/text-block
          :text/value "Inventory"
          :text/font-size 20}
  :search {:node/kind :ui/text-input
           :text/value ""
           :text/placeholder "Filter items..."
           :ui/focusable true}
  :items {:node/kind :ui/list
          :list/orientation :column
          :list/gap 8
          :node/children [:item-1 :item-2]}
  :item-1 {:node/kind :ui/button
           :node/children [:item-1-label]}
  :item-1-label {:node/kind :scene/text
                 :text/value "Potion"}
  :item-2 {:node/kind :ui/button
           :node/children [:item-2-label]}
  :item-2-label {:node/kind :scene/text
                 :text/value "Sword"}}}
```

## Tests

### Scene-level tests

- UI fragments validate against `scene.core` fragment invariants
- `:ui/text-block` preserves text layout fields
- `:ui/text-input` preserves cursor and selection fields
- `:ui/scroll` preserves axis and offset data
- `:ui/list` preserves ordered children
- `:ui/overlay` preserves overlay ordering metadata
- focusable and accessibility fields remain attached to scene values

### Lowering tests

- text blocks lower into explicit text graphics bytecode
- buttons lower into visible geometry plus text ops
- scroll nodes introduce clipping and offset semantics
- overlays lower in front of non-overlay siblings according to explicit ordering
- invisible UI nodes emit no graphics bytecode

## Accepted Defaults

These choices are fixed for v1:

- `scene.ui` is the first concrete domain extension
- it targets 2D apps and 2D game UI
- it extends `scene.core` rather than redefining composition
- it preserves UI semantics as data before terminal lowering
- it is not a DOM and not a browser compatibility layer
