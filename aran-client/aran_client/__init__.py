"""Aran Python client — run OQL against an Aran server, get a DataFrame.

    import aran_client as aran

    aran.connect("http://localhost:8080", token="aran_pat_…")
    df = aran.query("SELECT Paper.title, Paper.year FROM Paper")

The connection is a network client and nothing else. Aran's schema graph,
tenancy context and query planner stay server-side, by design — there is no
embedded or in-process mode, and this package will never grow one.
"""

from ._connection import Connection, connect, default_connection, query
from ._integration import (
    MappingCandidate,
    confirm_mapping,
    profile_dataset,
    propose_mapping,
    register_dataframe,
    wait_for_job,
)
from ._result import QueryResult
from ._widget import AlignmentWidget, review_mappings
from .errors import (
    AranAuthError,
    AranConfigurationError,
    AranError,
    AranHttpError,
    AranInsecureTransportError,
    AranQueryError,
    AranServerError,
)

__version__ = "0.1.0"

__all__ = [
    "AlignmentWidget",
    "AranAuthError",
    "AranConfigurationError",
    "AranError",
    "AranHttpError",
    "AranInsecureTransportError",
    "AranQueryError",
    "AranServerError",
    "Connection",
    "MappingCandidate",
    "QueryResult",
    "__version__",
    "confirm_mapping",
    "connect",
    "default_connection",
    "profile_dataset",
    "propose_mapping",
    "query",
    "register_dataframe",
    "review_mappings",
    "wait_for_job",
]
