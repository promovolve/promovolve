// Matte refinement — the "compensation" pass after the coarse u2netp
// alpha (remove-bg.ts). Two classical, deterministic, model-free steps,
// both using the ORIGINAL image as the source of truth for where the
// real edge is:
//
//  1. Guided filter (He, Sun, Tang 2010; the "fast" variant, 2015):
//     re-express the coarse alpha as a local linear function of the
//     full-res RGB guide, so the alpha edge snaps to the actual fur /
//     hair / product boundary instead of the model's 320px blur.
//     Inside the subject (alpha ≡ 1) and outside (alpha ≡ 0) the fit
//     is a=0, b=alpha — nothing changes; only the transition band moves.
//
//  2. Foreground decontamination: semi-transparent edge pixels still
//     carry the background's colour (green fringe on a dog cut out of
//     foliage). Estimate the true foreground colour at each edge pixel
//     as the alpha²-weighted mean of nearby foreground, and blend
//     toward it as alpha falls. remove.bg / rembg's alpha-matting do
//     the same thing with a heavier solver.
//
// Everything runs on a subsampled grid (long edge ≤ WORK_EDGE): the
// guided filter's linear coefficients and the foreground estimate are
// smooth fields, so computing them at ~1/3 resolution and bilinearly
// sampling them at full resolution loses nothing visible while keeping
// the whole pass at a few hundred ms and a few tens of MB on a 2000px
// image. The final per-pixel evaluation uses the FULL-res guide, which
// is where the fine detail comes back from.
//
// Pure typed-array code — no DOM — so tests/matte-refine.test.ts can
// pin the behaviour without a canvas.

export interface RefineOptions {
  /** Guided-filter radius in FULL-res pixels. Default scales with size. */
  radius?: number;
  /** Guided-filter regularizer on a 0..1 scale. Smaller = follows the
    * guide's edges more strictly. */
  eps?: number;
  /** Low-res working grid long edge. */
  workEdge?: number;
}

const DEFAULT_EPS = 1e-4;
const WORK_EDGE = 800;

// Post-filter contrast curve. The guided filter puts the alpha step in
// the RIGHT PLACE (on the guide's colour edge) but at reduced magnitude
// — a window straddling the edge fits alpha ≈ a·I + b whose two levels
// are pulled toward the window mean (≈0.85 / ≈0.2 in the synthetic
// sweep). A smoothstep between these two points restores a full 0→1
// step while keeping genuine partial coverage (hair strands, the
// anti-aliased pixel row) proportional. Anything inside the subject
// (q ≈ 1) or outside (q ≈ 0) is unaffected.
export const CONTRAST_LO = 0.2;
export const CONTRAST_HI = 0.8;

// Radius rule: the filter can only relocate alpha within ~radius of the
// guide edge, so it must cover the model's soft band — u2netp's 320px
// mask upsampled to a 2000px image blurs over roughly 20–30px. Larger
// windows widen the colour model (a same-coloured background object
// inside the window starts leaking in), so this is a floor, not a max.
const radiusFor = (longEdge: number): number => clampInt(Math.round(longEdge / 60), 6, 32);

/** Refine `alpha` (w×h, 0..255) against the RGBA guide `rgba` (w×h×4)
  * and decontaminate edge colours IN PLACE in `rgba`. Returns the
  * refined alpha (new array). */
export function refineMatte(
  rgba: Uint8ClampedArray,
  w: number,
  h: number,
  alpha: Uint8ClampedArray,
  opts: RefineOptions = {},
): Uint8ClampedArray {
  const longEdge = Math.max(w, h);
  const workEdge = opts.workEdge ?? WORK_EDGE;
  const s = Math.max(1, Math.ceil(longEdge / workEdge));
  const rFull = opts.radius ?? radiusFor(longEdge);
  const r = Math.max(1, Math.round(rFull / s));
  const eps = opts.eps ?? DEFAULT_EPS;

  const lw = Math.ceil(w / s);
  const lh = Math.ceil(h / s);
  const n = lw * lh;

  // ── Downsample guide (3 ch) + alpha to the working grid, 0..1 ──
  const Ir = new Float32Array(n);
  const Ig = new Float32Array(n);
  const Ib = new Float32Array(n);
  const p = new Float32Array(n);
  downsample(rgba, w, h, s, lw, lh, Ir, Ig, Ib, alpha, p);

  // ── Guided filter coefficients (a_r, a_g, a_b, b) on the work grid ──
  const tmp = new Float32Array(n);
  const box = (src: Float32Array): Float32Array => {
    const dst = new Float32Array(n);
    boxBlur(src, lw, lh, r, dst, tmp);
    return dst;
  };
  const mr = box(Ir), mg = box(Ig), mb = box(Ib), mp = box(p);
  const mul = (x: Float32Array, y: Float32Array): Float32Array => {
    const o = new Float32Array(n);
    for (let i = 0; i < n; i++) o[i] = x[i] * y[i];
    return o;
  };
  const mrp = box(mul(Ir, p)), mgp = box(mul(Ig, p)), mbp = box(mul(Ib, p));
  const mrr = box(mul(Ir, Ir)), mrg = box(mul(Ir, Ig)), mrb = box(mul(Ir, Ib));
  const mgg = box(mul(Ig, Ig)), mgb = box(mul(Ig, Ib)), mbb = box(mul(Ib, Ib));

  const ar = new Float32Array(n), ag = new Float32Array(n), ab = new Float32Array(n), b = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    // Covariance of (I, p) and variance of I (3×3), regularized.
    const cr = mrp[i] - mr[i] * mp[i];
    const cg = mgp[i] - mg[i] * mp[i];
    const cb = mbp[i] - mb[i] * mp[i];
    const vrr = mrr[i] - mr[i] * mr[i] + eps;
    const vrg = mrg[i] - mr[i] * mg[i];
    const vrb = mrb[i] - mr[i] * mb[i];
    const vgg = mgg[i] - mg[i] * mg[i] + eps;
    const vgb = mgb[i] - mg[i] * mb[i];
    const vbb = mbb[i] - mb[i] * mb[i] + eps;
    // a = Σ⁻¹ · cov via cofactors (Σ symmetric positive-definite).
    const c00 = vgg * vbb - vgb * vgb;
    const c01 = vrb * vgb - vrg * vbb;
    const c02 = vrg * vgb - vrb * vgg;
    const c11 = vrr * vbb - vrb * vrb;
    const c12 = vrb * vrg - vrr * vgb;
    const c22 = vrr * vgg - vrg * vrg;
    const det = vrr * c00 + vrg * c01 + vrb * c02;
    const inv = det !== 0 ? 1 / det : 0;
    const xr = (c00 * cr + c01 * cg + c02 * cb) * inv;
    const xg = (c01 * cr + c11 * cg + c12 * cb) * inv;
    const xb = (c02 * cr + c12 * cg + c22 * cb) * inv;
    ar[i] = xr; ag[i] = xg; ab[i] = xb;
    b[i] = mp[i] - xr * mr[i] - xg * mg[i] - xb * mb[i];
  }
  const Ar = box(ar), Ag = box(ag), Ab = box(ab), B = box(b);

  // Refined alpha on the work grid (feeds the foreground estimate).
  const q = new Float32Array(n);
  for (let i = 0; i < n; i++) q[i] = contrast(Ar[i] * Ir[i] + Ag[i] * Ig[i] + Ab[i] * Ib[i] + B[i]);

  // ── Foreground colour estimate on the work grid ──
  // alpha²-weighted local mean of the guide: opaque pixels dominate,
  // half-transparent (contaminated) ones barely count. Two radii: the
  // tight one where there's enough foreground mass nearby, the wide one
  // as fallback for thin structures.
  const Fr = new Float32Array(n), Fg = new Float32Array(n), Fb = new Float32Array(n);
  {
    const wgt = new Float32Array(n);
    for (let i = 0; i < n; i++) wgt[i] = q[i] * q[i];
    const wr = mul(Ir, wgt), wg = mul(Ig, wgt), wb = mul(Ib, wgt);
    const estimate = (rad: number): [Float32Array, Float32Array, Float32Array, Float32Array] => {
      const o = (src: Float32Array): Float32Array => {
        const d = new Float32Array(n);
        boxBlur(src, lw, lh, rad, d, tmp);
        return d;
      };
      return [o(wgt), o(wr), o(wg), o(wb)];
    };
    const [m1, r1, g1, b1] = estimate(Math.max(1, r * 2));
    const [m2, r2, g2, b2] = estimate(Math.max(2, r * 8));
    for (let i = 0; i < n; i++) {
      if (m1[i] > 0.02) {
        Fr[i] = r1[i] / m1[i]; Fg[i] = g1[i] / m1[i]; Fb[i] = b1[i] / m1[i];
      } else if (m2[i] > 0.005) {
        Fr[i] = r2[i] / m2[i]; Fg[i] = g2[i] / m2[i]; Fb[i] = b2[i] / m2[i];
      } else {
        Fr[i] = Ir[i]; Fg[i] = Ig[i]; Fb[i] = Ib[i];
      }
    }
  }

  // ── Full-res evaluation: q = A·I + B with the full-res guide;
  //    colour = lerp(F, C, q) so opaque pixels are untouched. ──
  const out = new Uint8ClampedArray(w * h);
  const smp = new Float32Array(7); // Ar Ag Ab B Fr Fg Fb
  const fields = [Ar, Ag, Ab, B, Fr, Fg, Fb];
  for (let y = 0; y < h; y++) {
    const fy = Math.min(lh - 1, Math.max(0, (y + 0.5) / s - 0.5));
    const y0 = Math.floor(fy), y1 = Math.min(lh - 1, y0 + 1), ty = fy - y0;
    for (let x = 0; x < w; x++) {
      const fx = Math.min(lw - 1, Math.max(0, (x + 0.5) / s - 0.5));
      const x0 = Math.floor(fx), x1 = Math.min(lw - 1, x0 + 1), tx = fx - x0;
      const i00 = y0 * lw + x0, i01 = y0 * lw + x1, i10 = y1 * lw + x0, i11 = y1 * lw + x1;
      const w00 = (1 - tx) * (1 - ty), w01 = tx * (1 - ty), w10 = (1 - tx) * ty, w11 = tx * ty;
      for (let k = 0; k < 7; k++) {
        const f = fields[k];
        smp[k] = f[i00] * w00 + f[i01] * w01 + f[i10] * w10 + f[i11] * w11;
      }
      const j = (y * w + x) * 4;
      const cr = rgba[j] / 255, cg = rgba[j + 1] / 255, cb = rgba[j + 2] / 255;
      const a = contrast(smp[0] * cr + smp[1] * cg + smp[2] * cb + smp[3]);
      out[y * w + x] = Math.round(a * 255);
      if (a < 0.995 && a > 0) {
        const t = 1 - a;
        rgba[j]     = Math.round((cr + (smp[4] - cr) * t) * 255);
        rgba[j + 1] = Math.round((cg + (smp[5] - cg) * t) * 255);
        rgba[j + 2] = Math.round((cb + (smp[6] - cb) * t) * 255);
      }
    }
  }
  return out;
}

// ─── Kernels ─────────────────────────────────────────────────────

/** Separable box blur with clamped (edge-normalized) windows. */
export function boxBlur(src: Float32Array, w: number, h: number, r: number, dst: Float32Array, tmp: Float32Array): void {
  // Horizontal → tmp
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let sum = 0;
    for (let x = 0; x < Math.min(r, w); x++) sum += src[row + x];
    for (let x = 0; x < w; x++) {
      if (x + r < w) sum += src[row + x + r];
      if (x - r - 1 >= 0) sum -= src[row + x - r - 1];
      const cnt = Math.min(x + r, w - 1) - Math.max(x - r, 0) + 1;
      tmp[row + x] = sum / cnt;
    }
  }
  // Vertical → dst
  for (let x = 0; x < w; x++) {
    let sum = 0;
    for (let y = 0; y < Math.min(r, h); y++) sum += tmp[y * w + x];
    for (let y = 0; y < h; y++) {
      if (y + r < h) sum += tmp[(y + r) * w + x];
      if (y - r - 1 >= 0) sum -= tmp[(y - r - 1) * w + x];
      const cnt = Math.min(y + r, h - 1) - Math.max(y - r, 0) + 1;
      dst[y * w + x] = sum / cnt;
    }
  }
}

/** Area-average downsample by integer factor s of an RGBA buffer (3
  * channels out, 0..1) and a parallel 8-bit alpha plane. */
function downsample(
  rgba: Uint8ClampedArray, w: number, h: number, s: number, lw: number, lh: number,
  Ir: Float32Array, Ig: Float32Array, Ib: Float32Array,
  alpha: Uint8ClampedArray, p: Float32Array,
): void {
  for (let ly = 0; ly < lh; ly++) {
    const y0 = ly * s, y1 = Math.min(h, y0 + s);
    for (let lx = 0; lx < lw; lx++) {
      const x0 = lx * s, x1 = Math.min(w, x0 + s);
      let sr = 0, sg = 0, sb = 0, sa = 0, cnt = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          const i = y * w + x;
          const j = i * 4;
          sr += rgba[j]; sg += rgba[j + 1]; sb += rgba[j + 2]; sa += alpha[i];
          cnt++;
        }
      }
      const li = ly * lw + lx;
      const k = 1 / (255 * cnt);
      Ir[li] = sr * k; Ig[li] = sg * k; Ib[li] = sb * k; p[li] = sa * k;
    }
  }
}

/** Smoothstep CONTRAST_LO..CONTRAST_HI, clamped to 0..1. */
function contrast(v: number): number {
  let t = (v - CONTRAST_LO) / (CONTRAST_HI - CONTRAST_LO);
  t = t < 0 ? 0 : t > 1 ? 1 : t;
  return t * t * (3 - 2 * t);
}

function clampInt(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}