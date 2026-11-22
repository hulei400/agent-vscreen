"""Score OCR pipelines on the screens made by gen.py.

The det/rec pipeline mirrors the app's VScreenPaddleOcr.kt (axis-aligned DB boxes, greedy CTC, wide-gap split), so a
score here is what the app would read. Metrics per pipeline:
  - char_acc: 1 - edit distance / GT length, per ground-truth element (OCR items whose centre falls in the element's
    box, joined left to right; spaces ignored), averaged over elements.
  - exact: share of elements read exactly (spaces and case ignored) - what `tap <words>` needs.
  - found: share of elements with at least one OCR item on them (detection recall).
  - ms: median wall time per screen (det + rec), 4 threads, on this x86 box (relative only; phones are ARM).
Usage: python bench.py <models dir> <name=det_dir:rec_dir[:det_long]> ...
"""
import glob, json, sys, time, os
import numpy as np, onnxruntime as ort
from PIL import Image

MD = sys.argv[1]
SCREENS = sorted(glob.glob(os.environ.get("SCREENS", "screens") + "/*.png"))


def sess(path):
    o = ort.SessionOptions(); o.intra_op_num_threads = 4; o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])


def cc(prob, th):
    """4-neighbour connected components with bbox, mean score."""
    from scipy import ndimage  # noqa
    lab, n = ndimage.label(prob > th)
    out = []
    if n == 0: return out
    objs = ndimage.find_objects(lab)
    sums = ndimage.sum(prob, lab, index=np.arange(1, n + 1))
    cnts = ndimage.sum(np.ones_like(prob), lab, index=np.arange(1, n + 1))
    for i, sl in enumerate(objs):
        y0, y1 = sl[0].start, sl[0].stop - 1; x0, x1 = sl[1].start, sl[1].stop - 1
        out.append((x0, y0, x1, y1, sums[i] / cnts[i]))
    return out


class Pipe:
    def __init__(self, det, rec, det_long=1280, thresh=0.2, box=0.45, unclip=1.4):
        self.det = sess(det); self.rec = sess(rec)
        d = os.path.join(os.path.dirname(rec), "dict.txt")
        self.chars = [""] + [l for l in open(d, encoding="utf-8").read().split("\n") if l] + [" "]
        self.det_long, self.th, self.box, self.unclip = det_long, thresh, box, unclip

    def run(self, img):
        a = np.asarray(img.convert("RGB"))
        h, w = a.shape[:2]
        r = min(1.0, self.det_long / max(w, h))
        rw = max(32, round(w * r / 32) * 32); rh = max(32, round(h * r / 32) * 32)
        small = np.asarray(img.convert("RGB").resize((rw, rh), Image.BILINEAR)).astype(np.float32) / 255.0
        # The app feeds B,G,R planes with mean/std in that plane order.
        bgr = small[..., ::-1]
        x = ((bgr - np.array([0.485, 0.456, 0.406])) / np.array([0.229, 0.224, 0.225])).transpose(2, 0, 1)[None].astype(np.float32)
        prob = self.det.run(None, {self.det.get_inputs()[0].name: x})[0][0, 0]
        sx, sy = w / rw, h / rh
        boxes = []
        for x0, y0, x1, y1, sc in cc(prob, self.th):
            bw, bh = x1 - x0 + 1, y1 - y0 + 1
            if min(bw, bh) < 3 or sc < self.box: continue
            d = bw * bh * self.unclip / (2 * (bw + bh))
            c = (int(max(0, (x0 - d) * sx)), int(max(0, (y0 - d) * sy)), int(min(w, (x1 + 1 + d) * sx)), int(min(h, (y1 + 1 + d) * sy)))
            if c[2] - c[0] >= 2 and c[3] - c[1] >= 2: boxes.append(c)
        items = []
        crops = []
        for c in boxes:
            cw, ch = c[2] - c[0], c[3] - c[1]
            nw = int(min(3200, max(8, np.ceil(48 * cw / ch))))
            sub = Image.fromarray(a[c[1]:c[3], c[0]:c[2]]).resize((nw, 48), Image.BILINEAR)
            arr = np.asarray(sub).astype(np.float32)[..., ::-1] / 127.5 - 1
            crops.append((c, arr.transpose(2, 0, 1), nw))
        crops.sort(key=lambda t: t[2])
        k = 0
        while k < len(crops):
            end = k + 1
            while end < len(crops) and end - k < 8 and (end - k + 1) * crops[end][2] <= 2400: end += 1
            batch = crops[k:end]; k = end
            mw = max(b[2] for b in batch)
            inp = np.zeros((len(batch), 3, 48, mw), np.float32)
            for i, b in enumerate(batch): inp[i, :, :, :b[2]] = b[1]
            out = self.rec.run(None, {self.rec.get_inputs()[0].name: inp})[0]
            steps = out.shape[1]; stride = mw / steps
            for i, b in enumerate(batch):
                usable = min(steps, int(np.ceil(b[2] / stride)) + 1)
                idx = out[i, :usable].argmax(1); val = out[i, :usable].max(1)
                chars = []; prev = 0; start = 0
                for t_ in range(usable + 1):
                    cur = idx[t_] if t_ < usable else -1
                    if cur != prev:
                        if prev != 0 and prev < len(self.chars): chars.append((self.chars[prev], (start + t_) / 2 * stride, val[start:t_].mean()))
                        prev = cur; start = t_
                if not chars or np.mean([c[2] for c in chars]) < 0.5: continue
                c = b[0]; kx = (c[2] - c[0]) / b[2]
                for ch, cx, _ in chars:
                    items.append((ch, c[0] + cx * kx, (c[1] + c[3]) / 2, c))
        return items


def norm(s): return "".join(s.split()).lower()


def lev(a, b):
    if not a: return len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1): cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


TINY = set(open(os.path.join(MD, "PP-OCRv6_tiny_rec", "dict.txt"), encoding="utf-8").read().split("\n")) if os.path.isdir(os.path.join(MD, "PP-OCRv6_tiny_rec")) else set()


def tags(g):
    t = []
    h = g["box"][3] - g["box"][1]
    t.append("small" if h < 42 else "normal")
    if g.get("dim"): t.append("dim")
    if TINY and any(not c.isspace() and c not in TINY for c in g["text"]): t.append("rare")
    if all(not c.isalnum() for c in g["text"].replace(" ", "")): t.append("symbol")
    return t


def score(pipe, errors=None):
    accs, exact, found, ms, by = [], 0, 0, [], {}
    total = 0
    tag = {}
    for p in SCREENS:
        meta = json.load(open(p[:-4] + ".json"))
        img = Image.open(p)
        t0 = time.perf_counter(); items = pipe.run(img); ms.append((time.perf_counter() - t0) * 1000)
        for g in meta["gt"]:
            x0, y0, x1, y1 = g["box"]; pad = 6
            got = sorted([it for it in items if x0 - pad <= it[1] <= x1 + pad and y0 - pad <= it[2] <= y1 + pad], key=lambda it: (round(it[2] / 20), it[1]))
            s = norm("".join(it[0] for it in got)); gtn = norm(g["text"])
            if not gtn or "symbol" in tags(g): continue
            total += 1
            a = max(0.0, 1 - lev(s, gtn) / len(gtn)); accs.append(a)
            exact += s == gtn; found += bool(got)
            L = by.setdefault(meta["layout"], [0, 0]); L[0] += a; L[1] += 1
            for tg in tags(g):
                T = tag.setdefault(tg, [0, 0, 0]); T[0] += a; T[1] += s == gtn; T[2] += 1
            if errors is not None and s != gtn: errors.append((p[-7:-4], g["text"], s))
    return {"char_acc": round(float(np.mean(accs)), 4), "exact": round(exact / total, 4), "found": round(found / total, 4),
            "ms": round(float(np.median(ms))), "n": total, "by_layout": {k: round(v[0] / v[1], 3) for k, v in by.items()},
            "by_tag": {k: [round(v[0] / v[2], 3), round(v[1] / v[2], 3), v[2]] for k, v in tag.items()}}


if __name__ == "__main__":
    res = {}
    for spec in sys.argv[2:]:
        name, rest = spec.split("=", 1)
        parts = rest.split(":")
        det, rec = os.path.join(MD, parts[0]), os.path.join(MD, parts[1])
        pipe = Pipe(det, rec, int(parts[2]) if len(parts) > 2 else 1280)
        pipe.run(Image.open(SCREENS[0]))  # warm-up
        errs = []
        r = score(pipe, errs)
        open("errors-%s.txt" % name, "w").write("\n".join("%s\t%s\t%s" % e for e in errs))
        r["mb"] = round((os.path.getsize(det) + os.path.getsize(rec)) / 1e6, 1)
        res[name] = r
        print(name, json.dumps(r, ensure_ascii=False), flush=True)
    json.dump(res, open("results-%d.json" % int(time.time()), "w"), ensure_ascii=False, indent=1)
