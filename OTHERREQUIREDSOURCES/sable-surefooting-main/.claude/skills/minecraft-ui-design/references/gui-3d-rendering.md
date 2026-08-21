# Custom 2D/3D geometry inside a Minecraft screen (1.21.x)

How to draw arbitrary shapes — polygons, ribbons, a rotatable 3D globe — inside a GUI,
and every silent failure mode met while building one. All of this was learned on a
rotating Goldberg-sphere widget with animated energy currents.

## The raw-drawing recipe

```java
graphics.flush();                       // commit GuiGraphics' batched draws FIRST
RenderSystem.enableBlend();
RenderSystem.defaultBlendFunc();
RenderSystem.disableCull();             // see "Culling" below
RenderSystem.setShader(GameRenderer::getPositionColorShader);
Matrix4f matrix = graphics.pose().last().pose();
BufferBuilder buffer = Tesselator.getInstance()
        .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
// ... buffer.addVertex(matrix, x, y, 0).setColor(r, g, b, a) triangles ...
MeshData mesh = buffer.build();
if (mesh != null) {                     // build() returns null for an empty buffer
    BufferUploader.drawWithShader(mesh);
}
RenderSystem.enableCull();
RenderSystem.disableBlend();
```

- **`graphics.flush()` before custom draws** — otherwise GuiGraphics' batched sprites
  and fills render AFTER yours and cover them.
- **Draw everything at z = 0 and order it yourself** (painter's algorithm). There is no
  useful depth buffer for this; sort back-to-front and insertion order wins.
- Convex polygons: triangle fan from the center point (`center, corner[i], corner[i+1]`).

## Silent failure modes (no log output, just wrong pixels)

- **Backface culling eats variable-winding quads.** Any quad strip whose vertex order
  depends on runtime direction (a ribbon between two arbitrary points) flips winding when
  the direction flips — culling removes it with zero errors. `RenderSystem.disableCull()`
  around the draw, re-enable after. Symptom: geometry visible from some angles only, or
  "the lines just don't render".
- **Alpha 0 tints render fully transparent.** ItemColor handlers and vertex colors must
  carry alpha: `0xFF000000 | rgb`. A bare `0x7A5B3C` has alpha 0 → invisible model,
  zero warnings. Symptom: model bakes, textures stitch, item invisible.
- **`item/generated` in a custom-elements model's parent chain** makes MC run the sprite
  generator and ignore your elements (renders from missing layer0). Custom `elements`
  models need no parent (plus their own `display` block) and per-element `tintindex`.

## 3D-on-a-sphere widget (orthographic globe)

- **Rotation**: keep a `Quaternionf orientation`; free-trackball mode is
  `orientation.premul(new Quaternionf().rotateY(dx*k).rotateX(dy*k))`. A locked-poles
  mode combines `rotateLocalY(dx*k)` for yaw with a clamped accumulated pitch premul —
  and check the yaw sign in lock mode against free mode (it inverted on us; playtesters
  notice immediately). Offer Recenter (reset to a stored default orientation).
- **Projection** is orthographic: `screen = center + (rotated.x, -rotated.y) * radius`.
  Front-face test: rotated z > 0.
- **Pick cells by projected polygon** (point-in-polygon over the projected corners), take
  the hit with the highest z. Use the *same* clipped polygon for picking that you drew,
  so invisible parts are never clickable.

### The horizon (limb) problem — three iterations, learn from them

1. Culling cells by **center z > 0** pops them out early: the center dips behind the
   horizon while part of the face is still visible. Playtest report: "edges de-render
   early".
2. Drawing the full polygon of any partially-visible cell is worse: orthographic
   projection maps behind-the-horizon points back **inside** the disc (for a unit-sphere
   point, |xy| = sqrt(1 − z²), so z < 0 still lands within the silhouette). The hidden
   part folds over neighbors and shows through any gaps between fills. Report: "renders
   through the cell dividers".
3. The fix that shipped, both parts required:
   - **Clip each cell polygon against the z ≥ 0 half-space in 3D, before projection**
     (Sutherland–Hodgman on z; interpolate crossing edges to z = 0).
   - **Fade the cell out as its center approaches the horizon** (alpha × clamp(z / 0.12)).
     The clip alone leaves a physically-correct sliver hugging the limb that *reads* as a
     glitch; the fade dissolves it before it can peek. Apply the same fade to anything
     attached to the cell (connection lines, rims), and stop picking at center z ≤ 0.

## Animated connection lines ("energy currents")

A ribbon between two projected points, built as `segments` quads along the line:

- Displace the midline by **two travelling sine waves** of different frequency/speed,
  multiplied by an envelope `sin(π t)` so both ends stay anchored at the cell centers.
- Width tapers with the same envelope (`0.55 + 0.45 * envelope`).
- Draw twice: wide low-alpha pass (glow) + narrow high-alpha pass (core).
- **Flow direction**: BFS depth over the link graph from designated source cells; orient
  each line from lower to higher depth. Seed depth 0 at the semantically meaningful
  sources (puzzle endpoints), fall back to lowest index per component.
- **A pulse that relays across segments**: compute the pulse wave in *global chain
  coordinates* — `s = (depth + t) / wavelength`, brightness `max(0, sin(2π(s − time*speed)))³`
  — so a crest exits one link exactly as it enters the next. Per-link phase jitter goes
  on the wiggle only, never on the pulse (it would break the relay).
- Color: gradient between the two endpoints' colors per segment; blend toward the pulse
  color by the pulse strength.

## Recompute caches, not per-frame graphs

Anything derived from the placed state (link pairs, BFS depths, unlinked sets) is cached
against the input map and recomputed only when the map actually changes
(`placed.equals(lastInput)` guard). Render code then only projects and draws — the 60 FPS
loop never rebuilds graphs.
