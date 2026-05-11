# Design: Scene Algebra

## Scope Note

This document defines the algebraic core of the **retained-graphics** scene
system. It is **not** part of the `mr-clean` pipeline. `mr-clean` compiles
Reagent-like authoring directly to `dao.postgraphics` frame programs and
does not produce scene fragments. See [mr-clean.md](mr-clean.md).

This algebra is reserved for future retained-graphics consumers: tooling that
needs to inspect a stable tree, systems that need to retarget one source to
multiple backends, or any authoring layer that wants semantic queries
(hit-testing, focus, accessibility) preserved as data before lowering.

## Summary

This document defines the algebraic core of the scene system for retained
graphics.

The intended pipeline for retained consumers is:

```text
retained authoring (not mr-clean)
-> scene fragments
-> assembled scene
-> graphics bytecode
-> terminal renderer
```

`scene.core` is the stable semantic contract for retained composition in v1.

Its purpose is to prevent premature collapse from authored scene meaning
directly into backend-shaped paint commands **for consumers that want to
preserve that meaning**.

The model is algebraic and compositional:

- fragments are values
- fragment transforms produce new fragments
- whole scenes are completed fragments
- graphics bytecode is the derived terminal form

## Carrier Sets

The scene algebra is organized around three sets.

### `F` — scene fragments

`F` is the primary carrier set.

A fragment is a valid rooted subtree value.

```text
F = { f | f is a valid rooted scene fragment }
```

### `S` — scenes

`S` is the set of valid whole scenes.

```text
S = { s | s is a valid top-level scene }
```

In v1, a scene is a completed fragment used as the top-level root of rendering.

### `O` — graphics bytecode frames

`O` is the set of valid terminal graphics bytecode frames.

```text
O = { o | o is a valid graphics bytecode frame }
```

Lowering maps scene values into `O`.

See [dao.postgraphics.md](../dao.postgraphics.md) for the concrete terminal
bytecode vocabulary used in v1.

## V1 Representation Decision

V1 uses scene maps, not scene datoms, as the primary representation for `F` and
`S`.

Reasons:

- simpler to implement than datom normalization in the first pass
- easier to inspect during development
- preserves semantic structure without forcing terminal lowering too early
- does not block a future datom projection if stronger inspectability is needed

Datom projection remains a valid future direction, but it is not the v1
implementation contract.

## Fragment Shape

Every fragment has this shape:

```clojure
{:fragment/root <node-id>
 :fragment/nodes {<node-id> <node-map> ...}}
```

### Fragment invariants

- `:fragment/root` is required
- `:fragment/root` must exist in `:fragment/nodes`
- `:fragment/nodes` is required
- every node ID in `:fragment/nodes` must be unique
- every child reference must point to a node in `:fragment/nodes`
- every node must be reachable from `:fragment/root`
- the fragment must be a finite rooted tree
- cycles are invalid

Fragments are closed values. No child reference may point outside the fragment.

## Scene Shape

A scene has this shape:

```clojure
{:scene/root <node-id>
 :scene/nodes {<node-id> <node-map> ...}}
```

### Scene invariants

- `:scene/root` is required
- `:scene/root` must exist in `:scene/nodes`
- `:scene/nodes` is required
- every node must be reachable from `:scene/root`
- the scene must be a finite rooted tree
- cycles are invalid

In v1, scenes are assembled from fragments and then re-labeled into scene
shape for lowering and rendering.

## Relationship Between `F` and `S`

Fragments are the primary compositional values.

Scenes are finalized fragment values used for rendering.

Operationally:

- components should produce fragments
- fragment operations assemble larger fragments
- final assembly yields a top-level fragment
- that top-level fragment is converted to scene shape
- lowering consumes the scene

## Core Node Kinds

`scene.core` supports exactly these node kinds in v1:

- `:scene/container`
- `:scene/text`
- `:scene/rect`
- `:scene/circle`
- `:scene/path`
- `:scene/group`
- `:scene/viewport`

Not in `scene.core` v1:

- `:scene/light`
- `:scene/camera`
- arbitrary user-defined node kinds

World, UI, and XR semantics should extend the core in their own vocabularies.

## Common Node Fields

Every node map must contain:

- `:node/kind`

Every node map may contain:

- `:node/visible`
  - boolean
  - default: `true`
- `:node/transform`
  - map
  - optional
- `:node/style`
  - map
  - optional

### `:node/transform`

When present, `:node/transform` may contain:

- `:translate`
  - `[x y z]`
  - default if omitted: `[0 0 0]`
- `:rotate`
  - `[rx ry rz]`
  - default if omitted: `[0 0 0]`
- `:scale`
  - `[sx sy sz]`
  - default if omitted: `[1 1 1]`

### `:node/style`

When present, `:node/style` may contain:

- `:fill`
  - color vector `[r g b]` or `[r g b a]`
- `:stroke`
  - color vector `[r g b]` or `[r g b a]`
- `:stroke-width`
  - non-negative number
- `:opacity`
  - number in `[0.0, 1.0]`
- `:depth`
  - number

These are semantic style fields, not terminal paint instructions.

## Structural Fields

Nodes that can contain children must use:

- `:node/children`
  - vector of child node IDs

Structural invariants:

- child order is significant
- every child ID must exist in the same fragment or scene map
- a child may have only one parent in v1

Nodes that may contain children in `scene.core` v1:

- `:scene/container`
- `:scene/group`
- `:scene/viewport`

Nodes that may not contain children in `scene.core` v1:

- `:scene/text`
- `:scene/rect`
- `:scene/circle`
- `:scene/path`

## Kind-Specific Node Contracts

### `:scene/container`

Required fields:

- `:node/kind`
- `:node/children`
- `:layout`

` :layout` is a map with:

- `:kind`
  - required
  - v1 supports only `:stack`
- `:direction`
  - required
  - one of `:row` or `:column`

Optional layout fields:

- `:gap`
  - non-negative number
  - default: `0`
- `:align`
  - one of `:start`, `:center`, `:end`, `:stretch`
- `:justify`
  - one of `:start`, `:center`, `:end`, `:space-between`, `:space-around`,
    `:space-evenly`
- `:padding`
  - number or edge map
- `:margin`
  - number or edge map
- `:width`
  - non-negative number
- `:height`
  - non-negative number
- `:min-width`
  - non-negative number
- `:min-height`
  - non-negative number
- `:max-width`
  - non-negative number
- `:max-height`
  - non-negative number

### `:scene/group`

Required fields:

- `:node/kind`
- `:node/children`

### `:scene/viewport`

Required fields:

- `:node/kind`
- `:node/children`
- `:viewport/rect`

` :viewport/rect` must be:

- `[x y width height]`

### `:scene/text`

Required fields:

- `:node/kind`
- `:text/value`

Optional fields:

- `:text/font-size`
  - positive number
- `:text/font-family`
  - string
- `:text/align`
  - one of `:start`, `:center`, `:end`

### `:scene/rect`

Required fields:

- `:node/kind`
- `:rect/size`

` :rect/size` must be:

- `[width height]`

### `:scene/circle`

Required fields:

- `:node/kind`
- `:circle/radius`

` :circle/radius` must be a positive number.

### `:scene/path`

Required fields:

- `:node/kind`
- `:path/data`

` :path/data` is opaque path payload in v1.

## Edge Maps

When `:padding` or `:margin` is a map, the allowed keys are:

- `:top`
- `:right`
- `:bottom`
- `:left`

All values must be non-negative numbers.

## Algebraic Orientation

V1 scene semantics should be designed as an algebra of fragment values.

That means:

- the primary objects are fragments
- composition happens by fragment operations
- scenes are final assembled values
- lowering is a pure transform from `S` to `O`

This is not a claim that every operation must be mathematically elegant. It is
a design rule: prefer explicit value transforms over hidden object mutation or
lifecycle semantics.

## Core Operations on `F`

The model should support these capabilities as pure operations over fragments.

### `empty-fragment`

Returns the identity starting point for fragment construction.

```text
empty-fragment : -> F
```

### `singleton`

Constructs a one-node fragment.

```text
singleton : N -> F
```

Where `N` is the set of valid node maps paired with an explicit node ID.

### `attach-child`

Attaches one fragment as a child of a parent node in another fragment.

```text
attach-child : F × node-id × F -> F
```

Precondition:

- node IDs between the two fragments must be disjoint, or one fragment must be
  renamed explicitly first

### `replace-subtree`

Replaces the subtree rooted at some node with another fragment.

```text
replace-subtree : F × node-id × F -> F
```

### `remove-subtree`

Removes the subtree rooted at some node.

```text
remove-subtree : F × node-id -> F
```

This is invalid when the target node is the fragment root, unless a separate
emptying or re-rooting rule is explicitly chosen.

### `set-children`

Sets the ordered children of a structural node.

```text
set-children : F × node-id × [node-id] -> F
```

### `transform-fragment`

Applies transform overlay semantics to the root node of a fragment.

```text
transform-fragment : F × transform-map -> F
```

### `overlay-style`

Applies style overlay semantics to the root node of a fragment.

```text
overlay-style : F × style-map -> F
```

### `finalize-scene`

Converts a completed fragment into scene shape.

```text
finalize-scene : F -> S
```

### `lower-scene->ops`

Lowers a scene into terminal graphics bytecode frames.

```text
lower-scene->ops : S -> O
```

## Desired Algebraic Properties

### Closure

Fragment-editing operations return valid fragments or explicit errors.

### Identity

`empty-fragment` is the identity starting point for construction.

### Referential transparency

Applying the same operation to the same fragment yields the same fragment.

### Structural explicitness

Parent-child relationships, layout, transforms, and visibility are carried in
data.

### Invariant preservation

Fragment operations must preserve fragment validity unless they fail explicitly.

### Associative assembly, modulo renaming

Fragment assembly should be associative once node-ID collisions are resolved by
an explicit renaming step.

### Lowering as a structure-preserving transform

Lowering should preserve structural meaning in the expected way:

- child order in the scene maps to op order where relevant
- visibility in the scene maps to omission from emitted graphics bytecode
- transform composition in the scene maps to transform propagation in emitted
  graphics bytecode

## Validation Rules

A fragment is invalid when:

- `:fragment/root` is missing
- `:fragment/nodes` is missing
- the root ID is not present in `:fragment/nodes`
- a node is missing `:node/kind`
- a node kind is unsupported
- a child reference points to a missing node
- a leaf node contains `:node/children`
- a container node is missing required layout fields
- the graph contains a cycle
- there are unreachable nodes in `:fragment/nodes`

A scene is invalid when:

- `:scene/root` is missing
- `:scene/nodes` is missing
- the root ID is not present in `:scene/nodes`
- any fragment-level node validity rule is violated
- there are unreachable nodes in `:scene/nodes`

Unreachable nodes are invalid in v1. Fragments and scenes are explicit rooted
trees, not bags of spare entities.

## Example Fragment

```clojure
{:fragment/root :panel
 :fragment/nodes
 {:panel {:node/kind :scene/container
          :layout {:kind :stack
                   :direction :column
                   :gap 8
                   :padding 12}
          :node/children [:title :hero]}
  :title {:node/kind :scene/text
          :text/value "Hello"
          :text/font-size 18
          :node/style {:fill [1.0 1.0 1.0 1.0]}}
  :hero {:node/kind :scene/rect
         :rect/size [120 40]
         :node/style {:fill [0.2 0.4 0.8 1.0]}}}}
```

## Example Scene

```clojure
{:scene/root :panel
 :scene/nodes
 {:panel {:node/kind :scene/container
          :layout {:kind :stack
                   :direction :column
                   :gap 8
                   :padding 12}
          :node/children [:title :hero]}
  :title {:node/kind :scene/text
          :text/value "Hello"
          :text/font-size 18
          :node/style {:fill [1.0 1.0 1.0 1.0]}}
  :hero {:node/kind :scene/rect
         :rect/size [120 40]
         :node/style {:fill [0.2 0.4 0.8 1.0]}}}}
```

## Accepted Defaults

These choices are fixed for v1:

- the scene vocabulary is the primary semantic contract
- the model is fragment-first and compositional
- fragments are the primary carrier set
- scenes are finalized fragments
- v1 representation is scene maps
- the authoring layer produces fragments, not final graphics bytecode
- graphics bytecode is derived from finalized scenes
- the scene is a tree, not a general graph
- child order is explicit and significant
- scene datoms remain a valid future direction if stronger inspectability is
  needed
