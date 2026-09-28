"""Track C gate: an OQL string in, a correctly shaped DataFrame out."""

from __future__ import annotations

import json

import pyarrow as pa
import pytest

import aran_client as aran
from aran_client.errors import (
    AranAuthError,
    AranConfigurationError,
    AranError,
    AranInsecureTransportError,
    AranQueryError,
    AranServerError,
)

from .conftest import EXPORT_PATH, parquet_bytes, sample_table

TOKEN = "aran_pat_abcdef123456"


def connect(server, **kwargs):
    return aran.Connection(server.base_url, TOKEN, **kwargs)


class TestQuery:
    def test_returns_pandas_frame_with_server_shape(self, server):
        df = connect(server).query("FIND Paper.title, Paper.year")

        assert list(df.columns) == ["http://aran/Paper#title", "http://aran/Paper#year"]
        assert len(df) == 3
        assert df["http://aran/Paper#year"].tolist() == [1964, 1997, 2026]

    def test_returns_polars_frame_on_request(self, server):
        df = connect(server).query("FIND Paper.title", into="polars")

        assert df.shape == (3, 2)
        assert df["http://aran/Paper#title"].to_list() == ["Bell", "GAV", "Aran"]

    def test_returns_arrow_table_on_request(self, server):
        table = connect(server).query("FIND Paper.title", into="arrow")

        assert isinstance(table, pa.Table)
        assert table.num_rows == 3

    def test_sends_oql_and_parquet_format_to_the_export_endpoint(self, server):
        connect(server).query("FIND Paper.title")

        assert len(server.requests) == 1
        request = server.requests[0]
        assert request["path"] == EXPORT_PATH
        assert request["body"] == {"oql": "FIND Paper.title", "format": "PARQUET"}

    def test_presents_the_token_as_a_bearer_credential(self, server):
        connect(server).query("FIND Paper.title")

        assert server.requests[0]["authorization"] == f"Bearer {TOKEN}"

    def test_rejects_an_empty_query_without_calling_the_server(self, server):
        with pytest.raises(ValueError, match="empty"):
            connect(server).query("   ")

        assert server.requests == []

    def test_rejects_an_unknown_format(self, server):
        with pytest.raises(ValueError, match="parquet"):
            connect(server).query("FIND Paper.title", format="avro")

    def test_rejects_an_unknown_frame_library(self, server):
        with pytest.raises(ValueError, match="into must be"):
            connect(server).query("FIND Paper.title", into="spark")


class TestRowCount:
    def test_reports_the_servers_count_without_decoding(self, server):
        result = connect(server).execute("FIND Paper.title")

        assert result.row_count == 3
        assert result.format == "PARQUET"

    def test_a_truncated_body_is_an_error_rather_than_a_short_frame(self, server):
        """The count header is the only evidence a parquet body is incomplete."""
        table = sample_table()
        short = pa.table({name: table[name][:1] for name in table.column_names})
        server.reply_with(
            lambda path, body: {
                "status": 200,
                "payload": parquet_bytes(short),
                # The server claims 3; the body carries 1.
                "headers": {"X-Aran-Row-Count": "3"},
            }
        )

        with pytest.raises(AranError, match="incomplete result"):
            connect(server).query("FIND Paper.title")

    def test_a_missing_count_header_still_yields_a_frame(self, server):
        server.reply_with(
            lambda path, body: {"status": 200, "payload": parquet_bytes(sample_table())}
        )

        result = connect(server).execute("FIND Paper.title")

        assert result.row_count == 3


class TestErrors:
    def test_a_rejected_query_carries_the_engines_own_message(self, server):
        server.problem(400, "Unknown property: Paper.titel")

        with pytest.raises(AranQueryError, match=r"Unknown property: Paper\.titel") as caught:
            connect(server).query("FIND Paper.titel")

        assert caught.value.status_code == 400

    def test_an_unknown_token_is_an_auth_error(self, server):
        server.problem(401, "Unauthorized")

        with pytest.raises(AranAuthError):
            connect(server).query("FIND Paper.title")

    def test_a_read_scoped_refusal_is_an_auth_error(self, server):
        server.problem(403, "This API token is read-only.")

        with pytest.raises(AranAuthError, match="read-only"):
            connect(server).query("FIND Paper.title")

    def test_a_server_fault_is_distinguishable_from_a_bad_query(self, server):
        server.problem(500, "materialize-oql returned no file path")

        with pytest.raises(AranServerError):
            connect(server).query("FIND Paper.title")

    def test_a_non_json_error_body_still_produces_a_useful_message(self, server):
        server.reply_with(
            lambda path, body: {"status": 502, "payload": b"<html>gateway down</html>"}
        )

        with pytest.raises(AranServerError, match="gateway down"):
            connect(server).query("FIND Paper.title")

    def test_an_unreachable_server_names_the_url(self):
        connection = aran.Connection("http://127.0.0.1:9", TOKEN, timeout=1.0)

        with pytest.raises(AranError, match="could not reach Aran"):
            connection.query("FIND Paper.title")


class TestServerPolicy:
    """Auth policy is the server's to decide; the client's job is to report it clearly."""

    @staticmethod
    def _policy_server(server, methods, require_tls=False, query_status=401):
        def responder(path, body):
            if path == "/api/auth/policy":
                return {
                    "status": 200,
                    "payload": json.dumps(
                        {"methods": methods, "requireTls": require_tls}
                    ).encode(),
                    "headers": {"Content-Type": "application/json"},
                }
            return {
                "status": query_status,
                "payload": json.dumps(
                    {"title": "Unauthorized", "status": query_status, "detail": "Unauthorized"}
                ).encode(),
                "headers": {"Content-Type": "application/problem+json"},
            }

        server.reply_with(responder)

    def test_reads_the_servers_policy(self, server):
        self._policy_server(server, ["oidc", "api-token-bearer"], require_tls=True)

        policy = connect(server).auth_policy()

        assert policy == {"methods": ["oidc", "api-token-bearer"], "requireTls": True}

    def test_a_refusal_by_policy_says_so_rather_than_blaming_the_token(self, server):
        # "The administrator turned this off" and "your token is wrong" are the same 401.
        self._policy_server(server, ["oidc"])

        with pytest.raises(AranAuthError, match="server policy, not a bad token"):
            connect(server).query("FIND Paper.title")

    def test_a_refusal_when_the_method_is_accepted_is_left_alone(self, server):
        # The method is allowed, so the token really is the problem; inventing a policy
        # explanation here would send the user down the wrong path.
        self._policy_server(server, ["oidc", "api-token-bearer"])

        with pytest.raises(AranAuthError) as caught:
            connect(server).query("FIND Paper.title")

        assert "server policy" not in str(caught.value)

    def test_a_server_without_the_endpoint_is_not_second_guessed(self, server):
        server.problem(401, "Unauthorized")

        with pytest.raises(AranAuthError) as caught:
            connect(server).query("FIND Paper.title")

        assert "server policy" not in str(caught.value)
        assert connect(server).auth_policy() is None


class TestTransportSecurity:
    """The token is a months-long bearer credential; plain HTTP puts it on the wire in clear."""

    def test_refuses_plain_http_to_a_remote_host(self):
        with pytest.raises(AranInsecureTransportError, match="plain HTTP"):
            aran.Connection("http://aran.example.org:8080", TOKEN)

    def test_allows_plain_http_to_localhost(self, server):
        # Nothing can be listening in between, and this is the whole local-dev path.
        aran.Connection("http://127.0.0.1:8080", TOKEN).close()
        aran.Connection("http://localhost:8080", TOKEN).close()

    def test_allows_https_anywhere(self):
        aran.Connection("https://aran.example.org", TOKEN).close()

    def test_opting_in_is_explicit(self):
        aran.Connection("http://aran.example.org", TOKEN, allow_insecure=True).close()

    def test_connect_passes_the_opt_in_through(self, monkeypatch):
        monkeypatch.setenv("ARAN_URL", "http://aran.example.org")
        monkeypatch.setenv("ARAN_TOKEN", TOKEN)

        with pytest.raises(AranInsecureTransportError):
            aran.connect()

        aran.connect(allow_insecure=True).close()


class TestConnect:
    def test_requires_a_url_and_a_token(self, monkeypatch):
        monkeypatch.delenv("ARAN_URL", raising=False)
        monkeypatch.delenv("ARAN_TOKEN", raising=False)

        with pytest.raises(AranConfigurationError, match="base_url"):
            aran.connect()

        with pytest.raises(AranConfigurationError, match="token"):
            aran.connect("http://localhost:8080")

    def test_reads_credentials_from_the_environment(self, server, monkeypatch):
        monkeypatch.setenv("ARAN_URL", server.base_url)
        monkeypatch.setenv("ARAN_TOKEN", TOKEN)

        connection = aran.connect()

        assert connection.base_url == server.base_url
        assert connection.query("FIND Paper.title").shape == (3, 2)

    def test_module_level_query_uses_the_last_connection(self, server, monkeypatch):
        monkeypatch.setenv("ARAN_URL", server.base_url)
        monkeypatch.setenv("ARAN_TOKEN", TOKEN)
        aran.connect()

        df = aran.query("FIND Paper.title")

        assert len(df) == 3

    def test_module_level_query_without_a_connection_says_what_to_call(self, monkeypatch):
        monkeypatch.setattr(aran._connection, "_default", None)

        with pytest.raises(AranConfigurationError, match=r"aran\.connect"):
            aran.query("FIND Paper.title")

    def test_the_token_is_never_repr_d_in_full(self, server):
        text = repr(connect(server))

        assert TOKEN not in text
        assert "aran_pat_" in text

    def test_closing_is_idempotent_and_usable_as_a_context_manager(self, server):
        with connect(server) as connection:
            connection.query("FIND Paper.title")
        # A second close must not raise — notebooks re-run cells.
        connection.close()


class TestCsv:
    def test_csv_decodes_to_a_frame_too(self, server):
        payload = b"title,year\nBell,1964\nGAV,1997\n"
        server.reply_with(
            lambda path, body: {
                "status": 200,
                "payload": payload,
                "headers": {"X-Aran-Row-Count": "2"},
            }
        )

        df = connect(server).query("FIND Paper.title", format="csv")

        assert list(df.columns) == ["title", "year"]
        assert len(df) == 2
        assert json.loads(json.dumps(df["year"].tolist())) == [1964, 1997]
