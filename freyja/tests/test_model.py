"""The committed join-discovery model must load under the pinned scikit-learn.

`models/model.pkl` is a joblib-pickled GradientBoostingRegressor, and scikit-learn
does not guarantee unpickling across minor versions. The requirements pin says to
re-verify the model when the bound is raised, but the mismatch that actually
happened needed no raise: `>=1.5.0,<1.8.0` resolved to 1.7.2 against a pickle
written by 1.6.1, and the only signal was an InconsistentVersionWarning buried in
pytest output that nothing failed on.

A comment cannot fail when it goes stale. This can.
"""

import os
import warnings

import joblib
import pytest
from sklearn.exceptions import InconsistentVersionWarning

MODEL_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models", "model.pkl"
)


def test_model_file_is_present():
    # profile_api tolerates a missing model at import and only fails the request that
    # needs it, so its absence would otherwise surface as a 500 during join discovery.
    assert os.path.exists(MODEL_PATH), f"join-discovery model missing at {MODEL_PATH}"


def test_model_loads_without_a_version_mismatch():
    with warnings.catch_warnings():
        warnings.simplefilter("error", InconsistentVersionWarning)
        try:
            model = joblib.load(MODEL_PATH)
        except InconsistentVersionWarning as exc:
            pytest.fail(
                "model.pkl was pickled by a different scikit-learn than the one "
                f"installed; join-discovery scores are unguaranteed. {exc}"
            )

    assert hasattr(model, "predict"), "loaded object is not a fitted estimator"


def test_model_predicts_on_the_feature_vector_profile_api_builds():
    """The 22 features, in the order profile_api sends them.

    A model whose expected feature count drifts from this list fails here rather
    than as a 500 from the sidecar mid-discovery.
    """
    import pandas as pd

    features = [
        "name_dist", "frequency_max", "uniqueness", "first_word", "frequency_4qo",
        "freq_word_containment", "len_avg_word", "words_cnt_max", "frequency_6qo",
        "len_max_word", "frequency_min", "frequency_3qo", "is_empty", "frequency_iqr",
        "entropy", "val_pct_std", "words_cnt_min", "cardinality", "words_cnt_sd",
        "val_pct_max", "len_min_word", "words_cnt_avg",
    ]

    model = joblib.load(MODEL_PATH)
    scores = model.predict(pd.DataFrame([{f: 0.0 for f in features}])[features])

    assert len(scores) == 1
    assert isinstance(float(scores[0]), float)
