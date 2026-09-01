# flatuniverse — a next-token transformer for 2D rigid-body physics

**Date:** 2026-08-21
**Status:** design approved, not implemented

## Goal

Test whether an autoregressive transformer trained on tokenized physics states
generalizes **out of distribution** — to geometry, object counts, horizons, and
latent material properties it never saw in training.

Secondary goal: keep every LLM mechanism intact (discrete tokens, softmax,
cross-entropy, sampling, attention) so the trained model is legible — attention
maps, embedding structure, and next-token distributions are all part of the
result.

**Non-goals.** Not a fast surrogate simulator; pymunk is already faster. Not a
scaling study. Not a physics engine — we use pymunk and do not write contact
solvers.

## Decisions

| Question | Decision |
|---|---|
| Purpose | OOD generalization (primary), LLM legibility (secondary) |
| World | 2D box, uniform gravity, walls, arbitrary shapes, collisions, rotation |
| Engine | pymunk 7.3.0 (Chipmunk2D). Verified: cp312 manylinux wheel installs. |
| Shapes | General from day one — circles, convex polygons, thick segments, compounds |
| Fixed | `g`, dt, friction, density (mass per pixel), world size |
| Latent | Per-episode restitution, never emitted, inferred in-context |
| Representation | Discrete tokens, one per scalar, shared 512-bin vocab |
| Geometry | Integer pixel lattice, minimum feature 1 px |
| Compute | RTX 2060 (fp16 AMP) primary; CPU viable at the small config |

### Rejected, with reasons

- **Strict regime OOD** (train free-fall, test collisions) — unlearnable by any
  model. A force term absent from all training data leaves no evidence to infer
  it from. Replaced by in-context latent inference.
- **Continuous regression head** — accurate but discards softmax, sampling,
  perplexity and calibration. Defeats the secondary goal.
- **Digit-wise tokenization** — better magnitude extrapolation, 4x sequence
  length. Held as fallback if quantization drift dominates results.
- **MoE for runtime** — sparsifies the FFN, which is 18% of compute (50% after
  sliding-window attention). Capacity at constant FLOPs, not speed. Retained as
  an interpretability arm, sequenced after a dense baseline.
- **Pairwise gravitation** — invisible next to contact forces, fights uniform g.

## 1. World and generator

256x256 pixel box, static walls, uniform `g` down. Bodies get `shape.density = p`
so pymunk derives mass and moment from geometry. Polygon vertices are integers,
so the geometry preamble is exactly representable.

Lines have zero area, therefore zero mass. Static segments for scenery; dynamic
rods need a thickness (pymunk segment radius).

**Determinism.** Fixed dt plus fixed seed makes an episode reproducible from
`(seed, config)`. A dataset is a seed list plus a generator commit hash.

**Sub-pixel state.** Geometry is on the integer pixel grid; state is NOT. A body
drifting 0.4 px/step, rounded to 1 px, aliases velocity into noise. Positions
tokenize at 1/2 px.

**Substepping.** Step at 1/240 s, emit every 8th step for 30 Hz output. Discrete
collision detection tunnels thin fast objects through walls at coarse dt.

**Emit at 30 Hz, not 60.** A bounce already spans under one frame at 30 Hz.
Coarser and contact becomes an unexplained discontinuity. Finer, and the token
budget goes to parabolas the model extrapolates trivially. Do not treat lowering
the emit rate as a free saving.

## 2. Data, storage, splits

One episode at N=3, T=90 is ~2040 tokens. ~40k episodes gives ~80M tokens =
~160 MB as `uint16`. Generation is single-digit ms/episode, under a minute across
8 cores.

**Store, do not regenerate on the fly.** Generation is ~1000x faster than
training, but a generator thread steals cores from CPU training, and 160 MB is
free. Storing also gives reproducibility and keeps the `.npy` path that makes the
CPU/GPU move a config change.

**Format.** `data/shards/train_00000.npy` — 2D `uint16` `[n_episodes, max_len]`,
plus lengths, plus `manifest.json` recording bin ranges, latent range, shape
families, seed range, and the **generator git hash**. Ground truth is defined by
generator code; a silent generator change invalidates a checkpoint with no other
trace.

**Episode-aligned, padded, loss-masked** — not a flat concatenated stream. Random
windows would start mid-episode with no preamble and no bounces yet, making the
in-context inference task unanswerable on those samples.

| Split | Content |
|---|---|
| `train` | triangles, boxes, circles; N in {2,3}; e in 0.2-0.6 |
| `eval_iid` | same distribution, unseen seeds |
| `eval_shape` | pentagons, hexagons, concave compounds, thick segments |
| `eval_count` | N in {5,6} |
| `eval_count_ctrl` | N=3 at the same reduced T as `eval_count` |
| `eval_restitution` | e in 0.05-0.15 and 0.75-0.95 |
| `eval_horizon` | 4x longer episodes, sliding-window rollout |

`eval_count` at N=6 does not fit a 2048 context at full T, so it runs shorter —
which changes count and horizon at once. `eval_count_ctrl` isolates the count
effect. Compare against the control, never against `eval_iid`.

## 3. Sequence layout and tokenizer

```
[BOS]
[SHAPES]
  [BODY][POLY]   x1 y1 x2 y2 x3 y3  [ENDBODY]    <- body-local integer verts
  [BODY][CIRCLE] r                  [ENDBODY]
  [BODY][SEG]    x1 y1 x2 y2 thick  [ENDBODY]
[ENDSHAPES]
[STEP] [B] x y th vx vy w  [B] x y th vx vy w  [B] ...
[STEP] ...
[EOS]
```

Vocab ~526: 512 shared value bins + ~14 specials. Bodies keep preamble order for
the whole episode; identity is carried by slot, so no body-index tokens.

| Field | Range | Resolution |
|---|---|---|
| `x, y` | 0-256 px | 1/2 px |
| local verts | +/-64 px | 1/4 px |
| `vx, vy` | +/-16 px/step | 1/16 px |
| `th` | 0-2pi | 0.7 deg |
| `w` | +/-2 rad/step | — |

Shared bins keep the embedding at 65k params rather than 393k — material when the
model is under 1M. The slot disambiguates meaning. Out-of-range values clip; log
the clip rate, and treat >0.1% as a range bug rather than a model failure.

Per step: `N*7 + 1` tokens. N=3 gives 22/step, so ctx 2048 holds ~90 steps.

**Explicit `[B]` delimiters are required**, not optional: with variable N, nothing
can key off fixed offsets, and count OOD would otherwise need a retokenize and
retrain.

**Masking.** Preamble is conditioning — masked. The first timestep is also
conditioning; **loss starts at step 2**. Otherwise the model is asked to predict
initial conditions from nothing.

**Absolute state, not deltas.** Absolute keeps walls learnable at fixed world
coordinates. The cost is the copy trap — position barely changes per step, so
"repeat the previous token" scores well and resembles learning. Mitigated by
mandatory baselines (section 6), not by switching to deltas.

**RoPE, not learned positional embeddings.** With variable N, timestep block
boundaries fall at different absolute offsets every episode, and at N=6 they fall
where a learned table has never seen one. Learned absolute embeddings would make
count and horizon OOD fail for reasons unrelated to physics.

## 4. Model and training

| | |
|---|---|
| d_model / layers / heads | 128 / 4 / 4 (head_dim 32) |
| FFN | GELU MLP, hidden 512, swappable `ffn_cls` |
| Norm / positional | pre-norm RMSNorm / RoPE |
| Attention | `F.scaled_dot_product_attention`; layers 0-2 local w=512, layer 3 global |
| Embedding | tied input/output, vocab 526 |
| Context | 2048 |
| Params | ~0.85M |

The top layer is global deliberately: restitution must be aggregated from bounces
scattered across the episode, and a fully-local stack would structurally prevent
the inference being tested.

| | |
|---|---|
| Optimizer | AdamW, betas (0.9, 0.95), wd 0.1, clip 1.0 |
| LR | 6e-4, cosine decay, 200-step warmup |
| Batch | 8 x 2048 = 16k tokens/step |
| Steps | ~5000 (~80M tokens) |
| Precision | fp16 AMP + GradScaler |
| Cost | ~270 TFLOPs — ~3-6 min on 2060, ~2.5 h on CPU |

**Two configs, one codebase.** `dev` (d=64, L=2, ctx 512, 200 episodes, 200 steps)
runs the whole pipeline in ~2 min on CPU. Its job is not to learn physics but to
prove the pipeline is wired — shapes flow, masks align, rollout decodes, mp4
renders. `full` targets the 2060.

**Fresh episodes throughout, no epochs.** Train and val loss should therefore
track almost exactly. Divergence means episodes are being reused — a bug, not
overfitting.

**Logging** — CSV, no wandb; runs are minutes and will be diffed in bulk.

1. **Per-field loss** (`x, y, th, vx, vy, w` separately). Aggregate loss is
   dominated by position, which is near-deterministic; a model that completely
   fails on `w` still posts a good headline number.
2. **Loss bucketed by distance-to-collision.**
3. **Copy and ballistic baselines**, every split, every eval.

**Exposure bias.** Training is teacher-forced, rollout is autoregressive. A model
with excellent teacher-forced loss routinely diverges 20 steps into a rollout,
having never seen a slightly-wrong state. Expect the loss to look fine while the
video is garbage. Mitigation: perturb input state tokens to neighbouring bins with
small probability during training. On by default; noise-off is a comparison arm,
and the gap is itself a result.

**Bin ranges come from the manifest**, never from constants in `train.py`. Two
owners of the same numbers produces a model trained on one quantization and
evaluated on another, silently.

No curriculum, no progressive context — both contaminate `eval_horizon`.

## 5. Rollout and rendering

**Build the renderer before training.** Render raw pymunk output, then render a
tokenize/detokenize round-trip of the same episode. Identical videos validate
generator, bin ranges, and tokenizer at once, before a model exists to blame.

**Constrained decoding.** The grammar is trivial (after `[STEP]` expect `[B]`,
then exactly 6 values). Mask logits to the valid set. Then log how often the
constraint bit: **structural violation rate** is a real metric, and the mask
would otherwise hide a model that never learned the format.

**Two sampling modes.** Greedy for all metrics. Sampled ensembles (16 rollouts,
T=1.0) for analysis: a calibrated model should show low entropy in free flight
and an entropy spike at contact, with the ensemble fanning out exactly where
physics becomes chaotic. Ensemble spread vs distance-to-collision is the payoff
for choosing discrete tokens.

**One renderer, pose-driven** — takes shape definitions plus per-body `(x, y, th)`
and draws with PIL at 256x256. Deliberately not pymunk's debug draw: model output
is not a pymunk space, and two render paths would make truth and prediction differ
for rendering reasons later chased as physics bugs.

Output: truth | prediction side by side, error curve below, mp4 via ffmpeg. 30 Hz
emit means a 30 fps mp4 plays in real time.

**Traps.** (a) Physics is y-up, images are y-down — flip once, in one function,
and verify gravity pulls down on screen; wrong, everything still looks plausible.
(b) Body-local vertices need rotate-then-translate; wrong order looks correct at
small angles. Verify with an asymmetric L, not a triangle.

## 6. Eval

### Baselines

| Baseline | Role |
|---|---|
| Copy (repeat last state) | Floor; catches the copy trap |
| Ballistic (const velocity + g, no contact) | The real bar — exactly correct except during contact |
| Quantization oracle (truth -> tokenize -> detokenize -> roll) | **Ceiling** |

The oracle is essential: it is the error a perfect model still makes given 1/2 px
bins. Without it, "the model is bad" and "the bins are coarse" are
indistinguishable.

### Metrics by horizon

**Token-level** — per-field teacher-forced loss, structural violation rate.

**Short horizon (<20 steps)** — pointwise position error vs step against all three
baselines, **bucketed by distance-to-nearest-collision** (-5..+5, plus free
flight). Ballistic is perfect in free flight, so the model's entire contribution
lives in the contact buckets; a mean over all tokens hides a model that never
learned contact.

**Long horizon** — pointwise error is meaningless. Post-collision dynamics are
chaotic and the oracle itself diverges. Use instead:

- **Energy monotonicity** — with friction and e <= 1, energy must never increase.
  Label-free, works on any rollout.
- **Interpenetration rate** — fraction of frames with overlapping predicted poses.
  A model that has not learned geometry phases objects through each other.
  Label-free.
- **Ensemble coverage** — does the 16-rollout ensemble contain the truth?

### Headline result

Per collision, compute implied restitution `e_hat = rebound speed / impact speed`,
and plot `e_hat` against **number of collisions already observed in the episode**.

If in-context inference is happening, `e_hat` starts at the training-set mean (the
prior) and converges toward the episode's true `e` as evidence accumulates. That
is an in-context learning curve for a physical latent, it answers the primary
question directly, and it works on the held-out restitution range. Behavioural, so
no probe training needed; a linear probe on hidden states is a follow-up, not the
primary measurement.

### Gates, in order

0. **Does it beat ballistic in the contact buckets on `eval_iid`?** If not, it
   never learned contact and every question below is moot.
1. `eval_shape` vs `eval_iid` — degradation ratio on contact-bucket error.
2. `eval_count` vs `eval_count_ctrl`.
3. `eval_restitution` — does `e_hat` convergence hold outside the training range?

Gates are fixed before results are seen. "Poor OOD generalization" and
"undertrained" share a signature, so **every OOD claim requires evidence the loss
curve plateaued**; report it alongside any negative result.

## Repo layout

```
flatuniverse/
  config.py       dev / full configs, bin ranges
  generator.py    pymunk episodes -> state arrays
  tokenizer.py    states <-> tokens, manifest owner
  model.py        transformer, swappable ffn_cls
  train.py        training loop, logging
  rollout.py      constrained autoregressive decode
  render.py       pose-driven renderer -> mp4
  eval.py         baselines, metrics, gates
  data/shards/    gitignored
  runs/           gitignored
```

## Environment

- pymunk 7.3.0, torch, numpy, pillow, ffmpeg.
- Turing (sm_75): **fp16 AMP only — bf16 is unsupported**; the `flash-attn`
  package requires sm_80 and will not build; use
  `F.scaled_dot_product_attention`.
- pip defaults to a private CodeArtifact index that 401s. Installs need an
  explicit `--index-url` (`.../whl/cu121` on the Legion, `.../whl/cpu` here).

## Stretch, explicitly out of scope for v1

- MoE FFN arm — do experts split free-flight from contact?
- Spin/friction-dominated regimes.
- Shape as a latent rather than conditioning.
- Positions-only tokenization (drop velocity, 43% shorter sequences).
- Model size sweep once the loss curve shows capacity binding.
