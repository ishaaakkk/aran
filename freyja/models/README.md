# `model.pkl`: the join-discovery ranking model

`profile_api.py` loads this file with `joblib.load` at import time and uses it to rank
join candidates. It is a **committed binary blob**, and loading a pickle runs it: a
pickle is a small program. So the file is only as safe as its provenance, which this
note records.

## What is actually in it

Read without executing it, via `pickletools.genops`. The opcodes name every class and
callable the pickle reconstructs, and this is the complete list for this file:

```
sklearn.ensemble._gb.GradientBoostingRegressor
joblib.numpy_pickle.NumpyArrayWrapper
numpy.ndarray
numpy._core.multiarray._reconstruct
numpy.dtype
```

Protocol 4, 144,345 bytes, 7 `STACK_GLOBAL` / 3 `REDUCE` / 2 `NEWOBJ` / 4 `BUILD` and
nothing else. There is no `os`, `subprocess`, `builtins.eval` or `posix.system`: the
opcodes an attacker would need are absent. CI repeats this check on every run (the
`supply-chain` job in `.github/workflows/ci.yml`).

To repeat the check by hand:

```bash
python -m pip install picklescan
python -m picklescan --path freyja/models/model.pkl
```

## Where it came from

It is the trained estimator from the FREYJA paper's experiments, carried into this repo
with the sidecar. **The training script was not preserved**, and nothing in this
repository can regenerate the model. That is a reproducibility problem, not a security
one, and it is tracked as an open item.

The practical consequence: the model cannot be retrained, audited against its training
data, or reproduced from source. If join-discovery quality ever needs to change, that
work starts with rebuilding the training pipeline, not with tuning this file.

## Why it is still a pickle

Migrating to ONNX or SafeTensors was considered and rejected. Both prevent an artefact
from executing code on load, and the opcode scan above already shows that this one does
not. Neither fixes the real problem, that nobody can regenerate the model. A migration
would need the model loaded, converted and revalidated against a training set that does
not exist.

The `scikit-learn` upper bound in `freyja/requirements.txt` is the constraint that keeps
this file loadable; sklearn does not guarantee pickle compatibility across minor
versions, and there is no way to re-emit this one.
