"""Normalization-scheme comparison for the bundled face embedding model.

Loads every aligned 112x112 crop from D:\\collage\\debug_crops
(t<timestampMs>_x<left>_y<top>.png), runs each through
app/src/main/assets/face_embedder.tflite under three candidate input
normalizations, and compares consecutive-frame cosine similarities
(same-person-appearing-now assumption) per scheme.
"""

import os
import re
import sys

import numpy as np
from PIL import Image

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    from ai_edge_litert import Interpreter  # type: ignore[no-redef]

CROPS_DIR = r"D:\collage\debug_crops"
MODEL_PATH = r"D:\collage\app\src\main\assets\face_embedder.tflite"
INPUT_SIZE = 112
EMBEDDING_DIM = 192
THRESHOLD = 0.5

FNAME_RE = re.compile(r"^t(\d+)_x(-?\d+)_y(-?\d+)\.png$", re.IGNORECASE)


def norm_128(px: np.ndarray) -> np.ndarray:
    """App's CURRENT normalization: (pixel - 127.5) / 128.0."""
    return (px - 127.5) / 128.0


def norm_127_5(px: np.ndarray) -> np.ndarray:
    """Exact [-1, 1] mapping: (pixel - 127.5) / 127.5."""
    return (px - 127.5) / 127.5


def norm_255(px: np.ndarray) -> np.ndarray:
    """Plain [0, 1] mapping: pixel / 255.0."""
    return px / 255.0


SCHEMES = [
    ("norm_128", norm_128),
    ("norm_127_5", norm_127_5),
    ("norm_255", norm_255),
]


def load_crops(crops_dir: str):
    """Return [(timestampMs, PIL.Image RGB 112x112)] sorted by timestamp."""
    files = [f for f in os.listdir(crops_dir) if f.lower().endswith(".png")]
    parsed = []
    for f in files:
        m = FNAME_RE.match(f)
        if not m:
            print(f"  [skip] filename does not match t<ts>_x<x>_y<y>.png: {f}")
            continue
        ts = int(m.group(1))
        parsed.append((ts, os.path.join(crops_dir, f)))
    parsed.sort(key=lambda t: t[0])
    crops = []
    for ts, path in parsed:
        img = Image.open(path).convert("RGB")
        if img.size != (INPUT_SIZE, INPUT_SIZE):
            img = img.resize((INPUT_SIZE, INPUT_SIZE), Image.BILINEAR)
        crops.append((ts, img))
    return crops


def make_interpreter(model_path: str):
    try:
        return Interpreter(model_path=model_path)
    except TypeError:
        return Interpreter(model_path)


def run_scheme(interpreter, input_idx: int, output_idx: int, crops, norm_fn):
    embeddings = []
    for _ts, img in crops:
        px = np.asarray(img).astype(np.float32)  # HWC, 0..255
        tensor = np.expand_dims(norm_fn(px).astype(np.float32), axis=0)  # 1HWC
        interpreter.set_tensor(input_idx, tensor)
        interpreter.invoke()
        emb = np.array(interpreter.get_tensor(output_idx)[0], dtype=np.float64)
        n = np.linalg.norm(emb)
        if n > 0:
            emb = emb / n
        embeddings.append(emb)
    sims = []
    for i in range(len(embeddings) - 1):
        sims.append(float(np.dot(embeddings[i], embeddings[i + 1])))
    return sims


def main() -> int:
    print(f"crops dir : {CROPS_DIR}")
    print(f"model     : {MODEL_PATH}")
    if not os.path.isdir(CROPS_DIR):
        print(f"ERROR: crops directory does not exist: {CROPS_DIR}")
        return 1
    if not os.path.isfile(MODEL_PATH):
        print(f"ERROR: model file does not exist: {MODEL_PATH}")
        return 1

    crops = load_crops(CROPS_DIR)
    print(f"loaded {len(crops)} crops")
    if len(crops) < 2:
        print("ERROR: need at least 2 crops to form consecutive pairs.")
        return 1
    print(f"timestamp range: {crops[0][0]} .. {crops[-1][0]} ms")

    interpreter = make_interpreter(MODEL_PATH)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]
    print(f"model input : shape={input_details['shape']} dtype={input_details['dtype']}")
    print(f"model output: shape={output_details['shape']} dtype={output_details['dtype']}")
    input_idx = int(input_details["index"])
    output_idx = int(output_details["index"])

    results = {}
    for name, fn in SCHEMES:
        sims = run_scheme(interpreter, input_idx, output_idx, crops, fn)
        arr = np.array(sims)
        below = int(np.sum(arr < THRESHOLD))
        results[name] = {
            "n_pairs": len(sims),
            "min": float(arr.min()),
            "mean": float(arr.mean()),
            "max": float(arr.max()),
            "below_0_5": below,
        }
        print(f"\n--- {name} ---")
        print(f"  pairs          : {len(sims)}")
        print(f"  min similarity : {arr.min():.4f}")
        print(f"  mean similarity: {arr.mean():.4f}")
        print(f"  max similarity : {arr.max():.4f}")
        print(f"  pairs < 0.5    : {below}/{len(sims)}")

    print("\n================ SIDE-BY-SIDE ================")
    print(f"{'scheme':<12}{'pairs':>7}{'min':>9}{'mean':>9}{'max':>9}{'< 0.5':>9}")
    for name, _ in SCHEMES:
        r = results[name]
        print(
            f"{name:<12}{r['n_pairs']:>7}{r['min']:>9.4f}"
            f"{r['mean']:>9.4f}{r['max']:>9.4f}"
            f"{r['below_0_5']:>4}/{r['n_pairs']:<4}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
