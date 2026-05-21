# Design: `dao.postgraphics` v3 — Precision Pass

## Summary

This document closes the remaining pixel-portability gaps in `dao.postgraphics`
v1 and v2. It does not add new ops or new VM state. Instead it pins down the
detailed rendering rules that v1 and v2 left to the host backend:

- text layout and glyph rendering
- image sampling and `:image/fit` exact behavior
- path stroke joins, caps, and tessellation
- numeric epsilon policy for boundary comparisons

V3 is the precision contract for VMs that need maximal cross-backend
determinism. For geometry, clipping, image fitting, and other fully specified
surfaces, a v3-conforming VM can agree pixel-for-pixel with another
v3-conforming VM. Text rendering remains the one documented exception unless a
shared reference font and shaper are supplied. A v1- or v2-conforming VM that
does not implement v3 may produce visually similar but not pixel-identical
output.

V3 is layered: a VM may declare itself v1-, v2-, or v3-conforming. V3
conformance implies v2 and v1 conformance.

## Numeric Epsilon Policy

Floating-point comparisons in `dao.postgraphics` are subject to precision loss.
V3 fixes a single epsilon for all spec-mandated boundary comparisons:

```
EPSILON = 1.0e-6
```

This is the same value as v1's Axis-Alignment Epsilon. V3 generalizes the scope
of that epsilon from axis-alignment of resolved 2D transforms to every
spec-mandated floating-point boundary comparison enumerated below. A
v1-conforming VM that already implements the Axis-Alignment Epsilon is
consistent with v3's policy for that subset; v3 conformance requires applying
`EPSILON` uniformly across the additional comparison sites.

This epsilon applies to:

- **2D affine matrix validity check** (v2 VM State). A condition like
  "`M[2]` is `0`" is checked as `|M[2]| < EPSILON`. Likewise "`M[10]` is `1`"
  is checked as `|M[10] - 1.0| < EPSILON`. This makes the check stable across
  matrix multiplication chains that should yield zero or one but accumulate
  small floating-point drift.

- **Top-left fill rule edge tests** (v2 Rasterization Fill Rule). A pixel
  center "exactly on" an edge means within `EPSILON` of the edge in
  screen-space pixel units. Two adjacent triangles whose shared edge is
  computed to slightly different values (within `EPSILON`) still cover the
  boundary pixels correctly under the rule.

- **Near-plane clipping** (v2 3D Pipeline Stages). A vertex is in front of the
  near plane when `w_c > EPSILON` and `z_c > -EPSILON`, not strict `> 0` and
  `>= 0`. Vertices within `EPSILON` of the plane are clipped (treated as on
  the plane).

- **NDC bound discard** (v2 3D Pipeline Stages). A fragment with NDC z value
  in `[-EPSILON, 1 + EPSILON]` and NDC x, y in `[-1 - EPSILON, 1 + EPSILON]`
  is kept; outside that, discarded.

- **Camera frustum validity**. `:camera3d/far ≤ :camera3d/near` is checked as
  `:camera3d/far - :camera3d/near < EPSILON`.

The epsilon does **not** apply to:

- Depth-test comparisons (`less-or-equal`). The depth comparison uses strict
  `≤` on the raw NDC z value. Producers that need biasing (e.g., line overlays
  on triangle surfaces) should use `:state/depth-write false` for the overlay
  draw, not rely on epsilon.

- Index validation (e.g., triangle indices in vertex range). Indices are
  integers; equality is exact.

- Color blending arithmetic. Source-over is computed at full floating-point
  precision; no epsilon is applied to alpha or color values.

VMs that operate in lower-precision arithmetic (e.g., 16-bit floats on some
GPUs) must scale `EPSILON` to their working precision but should remain
conservative (i.e., reject borderline matrices, clip borderline vertices)
rather than accept them.

## Text Rendering Conventions

V1's `:draw/text` op leaves font matching, glyph shaping, and rasterization
host-specific. V3 pins the producer-visible contract — coordinate spaces,
baseline definition, alignment, and font-size units — so producers can author
text positions deterministically. Glyph appearance (the rendered shape of each
character) remains host-specific unless a reference font is supplied; this is
acknowledged as the only documented divergence.

### Font Size

`:font-size` is in **screen pixels**. It is the EM box height of the font, not
the cap height or x-height. It is applied after the viewport transform; a
`:font-size` of `14` produces glyphs that are 14 pixels tall in EM box terms,
regardless of the current transform. This matches the convention of CSS, HTML
Canvas, and Flutter `TextStyle.fontSize`.

The current 2D transform's scale and rotation **do not** scale or rotate the
glyphs. Text is rendered in screen space at the projected position only. If a
producer wants scaled or rotated text, they must pre-rasterize text into an
image and use `:draw/image`.

(This matches v1's spirit — v1 says text rendering is intentionally simple.
V3 makes the simplicity explicit.)

### Position and Baseline

`:position` is `[x y]` in current transform space. The VM transforms this point
to screen space using the current 2D transform stack top, exactly as for any
other 2D point.

The transformed `:position` is the **baseline origin**:

- The y coordinate is the alphabetic baseline of the text run.
- The x coordinate is the start of the first glyph's advance, before the
  first glyph's left side bearing.

Glyphs are rendered along the baseline extending to the right (LTR only in v3).
RTL and bidirectional text are out of scope for v3.

### Alignment

`:align`, when present, controls horizontal positioning relative to the
transformed `:position`:

- `:start` — `:position` is the left edge of the first glyph's advance.
  Glyphs extend rightward. This is the default.
- `:center` — the text run's total advance width is centered on `:position`.
  The leftmost glyph starts at `position.x - total_advance/2`.
- `:end` — `:position` is the right edge of the last glyph's advance. Glyphs
  extend leftward (in layout terms; the drawing order is unchanged).

The advance width used for centering is the sum of per-glyph advances after
shaping, in screen pixels. Producers that need exact width measurements should
treat advance width as a host-specific value and not rely on it for layout.

### Font Family Resolution

`:font-family`, when present, names a font. The VM resolves it as follows:

1. If the host provides a font with that exact family name **and** can
   guarantee pixel-perfect metric parity with the upstream measurement
   capability for that family, use it.
2. Otherwise, fall back to the host's default monospace font if `:font-family`
   is `"monospace"`, default sans-serif if `"sans-serif"`, default serif if
   `"serif"`, or the host's default font for any other unmatched name.
3. If none of the above can guarantee metric parity, the VM MUST fall back to
   a **System Monospace** font where metrics are strictly known and stable.
   This realizes v1's Normative Text Metrics fallback rule.
4. The fallback choice is host-specific. Producers should use generic family
   names (`"monospace"`, `"sans-serif"`, `"serif"`) for portable frames.

If `:font-family` is omitted, the VM uses its host default font, subject to
the same metric-parity rule.

### Line Height

V1 pins standard line-height to **`1.2 × font-size`** in screen pixels. V3
preserves this: authors should treat a text run as a spatial block whose
height is `font-size × 1.2`, anchored on the alphabetic baseline. The leading
above the baseline is approximately `0.8 × font-size` (cap and ascender room)
and the leading below is approximately `0.4 × font-size` (descender room);
the exact split is host-specific but the total block height is `1.2 ×
font-size`.

V3 does not introduce a `:line-height` field. Higher-level layout capabilities
may override the 1.2 ratio, but they MUST emit `:draw/text` ops whose
`:position` values already reflect the chosen line-height; the VM has no
notion of multi-line layout.

### Glyph Shaping and Rasterization

V3 does not mandate a specific shaping library or rasterizer. Glyph outlines
and per-pixel coverage are host-specific. Producers must accept that:

- Glyph shapes vary by host (different font files, different hinting)
- Subpixel positioning behavior is host-specific
- Kerning and ligature application depends on the host shaper

For pixel-identical text across backends, a future version may specify a
canonical font + shaper (e.g., a bundled Noto Sans + HarfBuzz). V3 does not.

### Color

`:color`, when present, sets the glyph fill color. Glyph edges are
anti-aliased using analytical coverage (per v1 Rendering Conventions).
Subpixel-coverage values are written through the source-over blend.

## Image Rendering Conventions

V1's `:draw/image` defines the op surface. V3 pins the exact behavior of
`:image/fit`, the sampling filter, and out-of-bounds source coordinates.

### Sampling Filter

The default sampling filter is **bilinear**. A pixel center in the destination
rect samples the source image at the corresponding source coordinate; the four
nearest source texels are linearly weighted by their fractional distance from
the sample point. This matches CSS image rendering, Canvas2D, and Flutter's
default `FilterQuality.low`/`medium`.

V3 does not provide a state op to change the filter. Nearest-neighbor and
bicubic filtering are out of scope for v3.

### Source Coordinate Clamping

When `:image/src-rect` is outside the image bounds, source coordinates are
**clamped to the image edge** (no repeat, no mirror). This matches CSS
`background-repeat: no-repeat` and OpenGL `GL_CLAMP_TO_EDGE`.

Source coordinates that are exactly on the image's right or bottom edge sample
the last column or row of texels respectively, blended with the next row of
implicit clamped texels under bilinear filtering — i.e., the edge texels.

### `:image/fit` Exact Behavior

Let `src` be the effective source rect (the image bounds clipped by
`:image/src-rect` if present). Let `dst` be the resolved destination rectangle
in screen-space pixels after the `:draw/image` `:rect` has passed through the
current transform stack and the backend viewport transform. Let
`aspect_src = src.width / src.height` and `aspect_dst = dst.width / dst.height`.

**`:contain`** — scale uniformly to fit inside `dst`, preserving aspect ratio.
The image is centered within `dst`; portions of `dst` not covered by the image
are not modified (no fill color).

```
if aspect_src > aspect_dst:
  scaled_width  = dst.width
  scaled_height = dst.width / aspect_src
else:
  scaled_width  = dst.height * aspect_src
  scaled_height = dst.height
offset_x = dst.x + (dst.width  - scaled_width)  / 2
offset_y = dst.y + (dst.height - scaled_height) / 2
```

**`:cover`** — scale uniformly to fill `dst`, preserving aspect ratio. The
image is centered; portions of the image outside `dst` are not drawn.

```
if aspect_src > aspect_dst:
  scaled_width  = dst.height * aspect_src
  scaled_height = dst.height
else:
  scaled_width  = dst.width
  scaled_height = dst.width / aspect_src
offset_x = dst.x + (dst.width  - scaled_width)  / 2
offset_y = dst.y + (dst.height - scaled_height) / 2
```

**`:fill`** — stretch to fill `dst` exactly, ignoring aspect ratio. The image
covers `dst` completely.

```
scaled_width  = dst.width
scaled_height = dst.height
offset_x = dst.x
offset_y = dst.y
```

**`:none`** — draw the image at its source size (no scaling). The image is
**centered** within `dst`. Portions of the image outside `dst` are not drawn;
portions of `dst` not covered by the image are not modified.

```
scaled_width  = src.width
scaled_height = src.height
offset_x = dst.x + (dst.width  - scaled_width)  / 2
offset_y = dst.y + (dst.height - scaled_height) / 2
```

When `:image/fit` is omitted, the default is `:fill`.

### Opacity

`:opacity`, when present, modulates the per-pixel alpha of the sampled image
fragment by multiplication: `final.a = sampled.a × opacity`. The result is
then composited under source-over per v1 Rendering Conventions.

## Path Stroke Conventions

V1's `:draw/path` defines path geometry but not stroke join, cap, or
tessellation rules. V3 pins these.

### Stroke Position

A stroke of width `w` is centered on the path: `w/2` extends to each side of
the path's idealized centerline. This is the conventional graphics behavior
(SVG, Canvas2D, Flutter).

Stroke width is in screen pixels per v1's Base Value Conventions.

### Line Cap

V3 fixes the line cap to **butt** for v3 conformance. Butt caps end the stroke
exactly at the path's endpoint with no extension.

A future version may add `:stroke-cap` field with `:butt`, `:round`, `:square`
options. V3 does not.

### Line Join

V3 fixes the line join to **bevel** for v3 conformance. Bevel joins connect
two stroke segments with a straight line cutting off the outside corner.

A future version may add `:stroke-join` field with `:miter`, `:round`,
`:bevel` options. V3 does not.

The choice of bevel rather than miter avoids the unbounded length of miter
joins at acute angles, which would otherwise require a miter-limit field.

### Curve Tessellation

`:quad-to` and `:cubic-to` segments are tessellated into line segments before
stroking and filling. V3 fixes the tessellation tolerance:

```
TESSELLATION_TOLERANCE = 0.25  ; in screen pixels
```

A curve is subdivided until the maximum perpendicular distance from any line
segment to the curve is less than `TESSELLATION_TOLERANCE`. This matches the
default flatness used by Skia and most production 2D rasterizers and is fine
enough that further subdivision is invisible at typical display densities.

VMs may use any subdivision algorithm (de Casteljau, adaptive forward
differencing, GPU-side Loop-Blinn) as long as the resulting line segments
satisfy the tolerance condition.

### Fill Rule for Self-Intersecting Paths

V1 specifies non-zero winding for `:draw/path` filling. V3 keeps this rule
unchanged.

Self-intersecting paths under non-zero winding fill regions where the winding
number is non-zero. V3 makes this explicit: VMs must compute the winding number
correctly for arbitrary self-intersections and not collapse to even-odd
internally.

### Stroke and Fill Composition

When `:draw/path` has both `:fill` and `:stroke`, the fill is rasterized first,
then the stroke is rasterized on top. The stroke color thus appears over the
fill color along the path's edges. This matches SVG and Canvas2D defaults.

## Failure Semantics (v3 additions)

V3 adds these failure conditions to v1 and v2:

- `:font-size` is not a positive number
- `:align` is present but not one of `:start`, `:center`, `:end`
- `:image/fit` is present but not one of `:contain`, `:cover`, `:fill`, `:none`
- `:opacity` is present but not in `[0.0, 1.0]`
- `:font-family`, when present, is not a string
- `:font-size`, when present, is not a number

The 2D affine matrix validity check is now epsilon-based per the Numeric
Epsilon Policy section above. A frame that was rejected under v2's strict
check but passes under v3's epsilon check is valid for a v3-conforming VM
and invalid for a v2-only VM. Producers targeting both should construct their
matrices to satisfy the strict check.

## Accepted Defaults (v3 additions)

V3 fixes:

- `EPSILON = 1.0e-6` for boundary comparisons listed in the Numeric Epsilon
  Policy; consistent with v1's Axis-Alignment Epsilon, generalized to every
  spec-mandated floating-point comparison
- `:font-size` is in screen pixels (EM box height); not affected by the current
  transform
- standard line-height is `1.2 × font-size`; a text run occupies a spatial
  block of that height anchored on the alphabetic baseline (inherits v1's
  Normative Text Metrics)
- `:position` for `:draw/text` is the alphabetic baseline origin; default
  `:align` is `:start`; LTR only in v3
- font-family resolution falls back to **System Monospace** when no host font
  can guarantee metric parity for the requested family
- `:image/fit` default is `:fill`
- image sampling is bilinear with `CLAMP_TO_EDGE` source addressing
- stroke caps are butt; stroke joins are bevel; tessellation tolerance is
  0.25 screen pixels
- `:draw/path` `:fill` is computed under non-zero winding (unchanged from v1)
- when both `:fill` and `:stroke` are present on `:draw/path`, fill is
  rasterized before stroke

V3 explicitly does **not** specify:

- glyph shapes (the rendered appearance of individual characters); these
  remain host-specific until a future version bundles a canonical font + shaper
- nearest-neighbor or bicubic image filtering; only bilinear is in the contract
- non-bevel joins, non-butt caps, miter limits; deferred to a future version
- bidirectional or RTL text; v3 is LTR only

A v3-conforming VM with a bundled reference font would produce pixel-identical
output to another v3-conforming VM with the same font, modulo the documented
3D anti-aliasing concession from v2.
