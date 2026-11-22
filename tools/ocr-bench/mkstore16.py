# fp16 storage, fp32 compute: every float initializer is stored as fp16 and Cast back to float at the top of the
# graph. ORT folds the Casts when the session is built, so every kernel is the plain fp32 one on every CPU.
import sys, onnx, numpy as np
from onnx import numpy_helper, helper, TensorProto
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
casts, keep = [], []
for t in g.initializer:
    if t.data_type == TensorProto.FLOAT and np.prod(t.dims) >= 16:
        a = numpy_helper.to_array(t).astype(np.float16)
        h = numpy_helper.from_array(a, t.name + "__f16")
        keep.append(h)
        casts.append(helper.make_node("Cast", [h.name], [t.name], to=TensorProto.FLOAT, name=t.name + "__cast"))
    else:
        keep.append(t)
del g.initializer[:]
g.initializer.extend(keep)
nodes = list(g.node)
del g.node[:]
g.node.extend(casts + nodes)
onnx.checker.check_model(m)
onnx.save(m, dst)
print(dst, len(casts), "casts")
