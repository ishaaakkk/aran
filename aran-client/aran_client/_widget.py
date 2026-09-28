"""The accept/override step from Track D, as a widget you click through in a notebook.

**Why anywidget and not a custom ipywidget.** Google Colab blocks the traditional
custom-widget extension mechanism — nbextension/labextension — outright, so a conventional
ipywidget simply does not load there. anywidget ships its front end as an ES module the
notebook loads directly, which is the one approach that runs unmodified in Jupyter,
JupyterLab, VS Code *and* Colab. This is a hard requirement, not a preference.

**Scope is one interaction, deliberately.** Column, top proposal, confidence, accept or
override. It is not the Alignments view: that solves general N-way reconciliation across a
whole model, and this solves "I have a DataFrame and a schema, are these columns the same
thing". Rebuilding the former in a notebook cell would be a much larger thing that fits
the task worse.

Nothing here matches anything. Every row starts as a *proposal* with a score, and the
widget's whole purpose is to make a human agree or disagree before anything is written.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import TYPE_CHECKING, Any

from ._integration import MappingCandidate, confirm_mapping
from .errors import AranError

if TYPE_CHECKING:  # pragma: no cover - typing only
    from ._connection import Connection

__all__ = ["AlignmentWidget", "review_mappings"]

_IMPORT_HINT = (
    "the alignment widget needs anywidget. Install it with "
    "`pip install aran-client[notebook]`."
)

# The front end. Plain ES module, no imports: a notebook may be offline, Colab sandboxes
# the output frame cross-origin, and a CDN fetch would fail in both.
_ESM = """
// Metric names are the server's, and the set of them grows: Aran ships a catalog of
// similarity metrics rather than a fixed pair. So this renders whatever arrives and
// only prettifies the names it happens to recognise.
const METRIC_LABELS = {
  linguisticScore: "name", nameScore: "name", nameSimilarity: "name",
  valueScore: "values", valueOverlapScore: "values", valueOverlap: "values",
};

function metricLabel(key) {
  if (METRIC_LABELS[key]) return METRIC_LABELS[key];
  // camelCase → spaced words, so an unrecognised metric still reads as English.
  return key.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
}

function scoreLabel(c) {
  const metrics = c.metrics || {};
  const parts = Object.keys(metrics).map(
    (k) => `${metricLabel(k)} ${Math.round(metrics[k] * 100)}%`);
  if (c.score === null || c.score === undefined) {
    return parts.length ? parts.join(", ") : "no score";
  }
  const pct = Math.round(c.score * 100);
  return parts.length ? `${pct}%  (${parts.join(", ")})` : `${pct}%`;
}

function render({ model, el }) {
  el.innerHTML = "";
  const root = document.createElement("div");
  root.className = "aran-align";

  const candidates = model.get("candidates") || [];
  // Decisions are seeded here rather than in Python so the widget is usable the moment it
  // renders: doing nothing and pressing Confirm accepts every proposal, which is the
  // common case, and skipping is the deliberate act.
  const decisions = candidates.map((c, i) => ({
    index: i, action: "accept", target: c.target,
  }));

  if (candidates.length === 0) {
    root.textContent = "No proposals to review.";
    el.appendChild(root);
    return;
  }

  const header = document.createElement("p");
  header.className = "aran-align-note";
  header.textContent =
    candidates.length + " proposal(s). These are suggestions with confidence scores — " +
    "accept, correct or skip each one. Nothing is written until you confirm.";
  root.appendChild(header);

  candidates.forEach((c, i) => {
    const row = document.createElement("div");
    row.className = "aran-align-row";

    const left = document.createElement("div");
    left.className = "aran-align-source";
    left.textContent = c.source_label || c.source;
    row.appendChild(left);

    const arrow = document.createElement("span");
    arrow.className = "aran-align-arrow";
    arrow.textContent = "→";
    row.appendChild(arrow);

    const target = document.createElement("input");
    target.type = "text";
    target.className = "aran-align-target";
    target.value = c.target;
    target.addEventListener("input", () => {
      decisions[i].target = target.value;
      // Editing IS the override. Making the user also select a radio would mean a typed
      // correction that silently did nothing.
      decisions[i].action = target.value === c.target ? "accept" : "override";
      paintState(i);
    });
    row.appendChild(target);

    const score = document.createElement("span");
    score.className = "aran-align-score";
    score.textContent = scoreLabel(c);
    row.appendChild(score);

    const skip = document.createElement("button");
    skip.className = "aran-align-skip";
    skip.textContent = "skip";
    skip.addEventListener("click", () => {
      decisions[i].action = decisions[i].action === "skip" ? "accept" : "skip";
      paintState(i);
    });
    row.appendChild(skip);

    const state = document.createElement("span");
    state.className = "aran-align-state";
    row.appendChild(state);

    function paintState(idx) {
      const d = decisions[idx];
      state.textContent = d.action;
      row.dataset.action = d.action;
    }
    paintState(i);

    root.appendChild(row);
  });

  const confirm = document.createElement("button");
  confirm.className = "aran-align-confirm";
  confirm.textContent = "Confirm";
  confirm.addEventListener("click", () => {
    // One assignment of a fresh array: traitlets syncs on identity, so mutating in place
    // would leave Python with the values it started with.
    model.set("decisions", decisions.map((d) => ({ ...d })));
    model.set("submitted", true);
    model.save_changes();
    confirm.textContent = "Confirmed";
    confirm.disabled = true;
  });
  root.appendChild(confirm);

  el.appendChild(root);
}

export default { render };
"""

_CSS = """
.aran-align {
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  color: #E8E8EE;
  background: #13131A;
  border: 1px solid #22222B;
  border-radius: 10px;
  padding: 14px 16px;
  max-width: 780px;
}
.aran-align-note { color: #7E7E8A; font-size: 12px; margin: 0 0 10px; }
.aran-align-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 0; border-bottom: 1px solid #22222B;
}
.aran-align-row[data-action="skip"] { opacity: 0.45; }
.aran-align-source { flex: 0 0 30%; font-family: ui-monospace, monospace; font-size: 12px; }
.aran-align-arrow { color: #7E7E8A; }
.aran-align-target {
  flex: 1; background: #191921; border: 1px solid #2D2D38; border-radius: 6px;
  color: #E8E8EE; font-family: ui-monospace, monospace; font-size: 12px; padding: 4px 6px;
}
.aran-align-score { flex: 0 0 30%; color: #7E7E8A; font-size: 11px; text-align: right; }
.aran-align-skip {
  background: transparent; border: 1px solid #2D2D38; border-radius: 6px;
  color: #7E7E8A; cursor: pointer; font-size: 11px; padding: 3px 8px;
}
.aran-align-state { flex: 0 0 66px; font-size: 11px; color: #7E7E8A; }
.aran-align-row[data-action="override"] .aran-align-state { color: #E8B339; }
.aran-align-confirm {
  margin-top: 12px; background: #8B5CF6; border: none; border-radius: 8px;
  color: #FFFFFF; cursor: pointer; font-weight: 600; padding: 8px 18px;
}
.aran-align-confirm:disabled { background: #3DCEA0; cursor: default; }
"""


def _widget_base():
    """Import anywidget lazily so the SDK's query path never depends on it."""
    try:
        import anywidget
        import traitlets
    except ImportError as exc:  # pragma: no cover - depends on env
        raise AranError(_IMPORT_HINT) from exc
    return anywidget, traitlets


def _build_class():
    anywidget, traitlets = _widget_base()

    class _AlignmentWidget(anywidget.AnyWidget):
        _esm = _ESM
        _css = _CSS

        candidates = traitlets.List().tag(sync=True)
        decisions = traitlets.List().tag(sync=True)
        submitted = traitlets.Bool(False).tag(sync=True)

    return _AlignmentWidget


class AlignmentWidget:
    """A reviewable set of proposals.

    Wraps the anywidget rather than subclassing it, for two reasons: the SDK's query path
    must not import anywidget at all, and the useful surface here — :attr:`accepted`,
    :meth:`apply` — is about mappings rather than about traitlets.

    Display it in a cell, click through it, then read :attr:`accepted` or call
    :meth:`apply`. The result is an ordinary Python value the moment the click-through
    ends, which is the whole point of the two-way channel.
    """

    def __init__(self, candidates: Sequence[MappingCandidate]):
        self._candidates = list(candidates)
        self._widget = _build_class()()
        self._widget.candidates = [_to_row(c) for c in self._candidates]

    def _ipython_display_(self):  # pragma: no cover - notebook only
        from IPython.display import display

        display(self._widget)

    def _repr_mimebundle_(self, **kwargs):  # pragma: no cover - notebook only
        return self._widget._repr_mimebundle_(**kwargs)

    @property
    def widget(self) -> Any:
        """The underlying anywidget, if you want to compose it into a larger layout."""
        return self._widget

    @property
    def submitted(self) -> bool:
        return bool(self._widget.submitted)

    @property
    def decisions(self) -> list[Mapping[str, Any]]:
        """Every decision, including skips — the audit of what the human actually did."""
        return list(self._widget.decisions)

    @property
    def accepted(self) -> list[MappingCandidate]:
        """The candidates to write, with any typed correction already applied.

        Empty until the widget is confirmed. That is deliberate: reading it early should
        return nothing rather than a set of defaults nobody agreed to.
        """
        if not self.submitted:
            return []
        out: list[MappingCandidate] = []
        for decision in self._widget.decisions:
            if decision.get("action") == "skip":
                continue
            index = decision.get("index")
            if not isinstance(index, int) or not 0 <= index < len(self._candidates):
                continue
            raw = dict(self._candidates[index].as_dict())
            raw["targetIri"] = decision.get("target", raw.get("targetIri"))
            out.append(MappingCandidate(raw))
        return out

    def apply(self, connection: Connection, *, project_id: str,
              comment: str | None = None) -> list[Mapping[str, Any]]:
        """Write every accepted mapping, through the endpoint the UI's confirm uses.

        Refuses before confirmation rather than writing the defaults: the widget exists so
        a human decides, and applying an un-clicked widget would quietly skip that.
        """
        if not self.submitted:
            raise AranError(
                "nothing has been confirmed yet — display the widget, review the "
                "proposals and press Confirm first"
            )
        return [
            confirm_mapping(connection, candidate, project_id=project_id, comment=comment)
            for candidate in self.accepted
        ]

    def __repr__(self) -> str:
        state = "confirmed" if self.submitted else "awaiting review"
        return f"<AlignmentWidget {len(self._candidates)} proposal(s), {state}>"


def _to_row(candidate: MappingCandidate) -> Mapping[str, Any]:
    """Flatten a candidate for the front end — plain JSON, no nested evidence to dig through.

    ``metrics`` carries the full similarity catalog. The front end iterates it rather than
    reading named fields, so Aran can add a metric without this file changing.
    """
    return {
        "source": candidate.source,
        "source_label": _local_name(candidate.source),
        "target": candidate.target,
        "score": candidate.score,
        # The whole catalog, not a fixed pair — a metric added server-side reaches the
        # widget without a client release.
        "metrics": dict(candidate.metrics),
    }


def _local_name(iri: str) -> str:
    for separator in ("#", "/"):
        if separator in iri:
            return iri.rsplit(separator, 1)[-1] or iri
    return iri


def review_mappings(candidates: Iterable[MappingCandidate]) -> AlignmentWidget:
    """Display proposals for a human to accept, correct or skip.

        w = aran.review_mappings(aran.propose_mapping(...))
        w                                    # click through it in the next cell's output
        w.apply(conn, project_id=...)        # writes only what was accepted
    """
    return AlignmentWidget(list(candidates))
