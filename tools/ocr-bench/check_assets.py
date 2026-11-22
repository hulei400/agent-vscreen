"""Build gate (1.4.0-beta2): the OCR models in the APK must build and read with the SAME onnxruntime version as the app.

beta1 shipped models benchmarked on a newer desktop runtime; the phone's runtime could not build them. This reads
assets/vscreen-ocr/selftest.png with the app's det.onnx/rec.onnx through bench.py's pipeline (a mirror of
VScreenPaddleOcr.kt) at ORT_ENABLE_ALL and fails unless the expected words come out. Phones run ARM kernels this
x86 box cannot; the app repeats the same self-test on the phone at service start and in `vscreen diag`.
Usage: python check_assets.py <app/src/main/assets/vscreen-ocr> <onnxruntime version the app uses>
"""
import os, sys, tempfile, shutil
d, want = sys.argv[1], sys.argv[2]
sys.argv = [sys.argv[0], d]  # bench.py reads argv[1] at import
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import onnxruntime as ort
from PIL import Image
import bench
if ort.__version__ != want:
    sys.exit(f"onnxruntime {ort.__version__} here, the app uses {want}: install the same version")
tmp = tempfile.mkdtemp()
try:
    shutil.copy(f"{d}/rec.onnx", f"{tmp}/model.onnx"); shutil.copy(f"{d}/dict.txt", f"{tmp}/dict.txt")
    items = bench.Pipe(f"{d}/det.onnx", f"{tmp}/model.onnx").run(Image.open(f"{d}/selftest.png"))
finally:
    shutil.rmtree(tmp)
lines = {}
for ch, x, y, c in items: lines.setdefault(tuple(c), []).append((x, ch))
words = ["".join(ch for _, ch in sorted(v)) for v in lines.values()]
expect = ["取消", "Search", "收藏", "已开启"]
miss = [w for w in expect if not any(w in x for x in words)]
print(f"ocr assets on onnxruntime {ort.__version__}: {len(words)} lines; expected {len(expect) - len(miss)}/{len(expect)}")
if miss: sys.exit(f"OCR self-test missed {miss}; read: {words[:20]}")
