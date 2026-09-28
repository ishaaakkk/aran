"""PyIceberg reads a table Aran's produce side generated (V53).

``fixtures/iceberg_product`` is ``IcebergProductTable``'s real output — metadata JSON, manifest
list and manifest written by iceberg-core's commit machinery — over a DuckDB-written parquet
that carries no Iceberg field ids, so the columns resolve only through the generated
``schema.name-mapping.default``. Regenerate it with ``PyIcebergFixtureGeneratorTest`` whenever
the generator changes.

Every location in the fixture is under ``file:///iceberg_product``; ``FixtureFileIO`` serves
``file:///`` out of the ``fixtures`` directory, so PyIceberg's own PyArrow FileIO reads every
byte from the checked-in files with no path from the machine that generated them.

What this does NOT show: a PyIceberg read of a live Aran. The produce surface serves its files
over authenticated ``https://``, and PyIceberg has no FileIO for that scheme — pinned below, so
the day it gains one this test says so instead of the docs going stale.

pyiceberg is a dev-only dependency; nothing in ``aran_client`` imports it.
"""

from __future__ import annotations

import datetime as dt
import json
import shutil
from decimal import Decimal
from pathlib import Path

import pytest

pyiceberg = pytest.importorskip("pyiceberg", reason="dev extra; needs Python >= 3.10")

import pyarrow as pa  # noqa: E402
import pyarrow.fs as pafs  # noqa: E402
from pyiceberg.io import load_file_io  # noqa: E402
from pyiceberg.io.pyarrow import PyArrowFileIO  # noqa: E402
from pyiceberg.table import StaticTable  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"
METADATA = "file:///iceberg_product/metadata/v1.metadata.json"


class FixtureFileIO(PyArrowFileIO):
    """PyArrow's local FileIO with ``/`` re-rooted at ``fixture-root`` (default: ``fixtures``)."""

    def _initialize_local_fs(self) -> pafs.FileSystem:
        root = self.properties.get("fixture-root", str(FIXTURES))
        return pafs.SubTreeFileSystem(root, pafs.LocalFileSystem())


@pytest.fixture(scope="module")
def table() -> StaticTable:
    return StaticTable.from_metadata(
        METADATA, {"py-io-impl": f"{__name__}.{FixtureFileIO.__name__}"}
    )


def test_schema_is_the_products_recorded_output_schema(table: StaticTable) -> None:
    arrow = table.scan().to_arrow()

    assert arrow.schema.names == ["year", "title", "published", "score", "updated_at"]
    assert arrow.schema.field("year").type == pa.int64()
    assert arrow.schema.field("title").type in (pa.string(), pa.large_string())
    assert arrow.schema.field("published").type == pa.date32()
    assert arrow.schema.field("score").type == pa.decimal128(38, 9)
    assert arrow.schema.field("updated_at").type == pa.timestamp("us")


def test_rows_resolve_through_the_name_mapping(table: StaticTable) -> None:
    rows = sorted(table.scan().to_arrow().to_pylist(), key=lambda r: r["year"])

    assert rows == [
        {
            "year": 2021,
            "title": "Aran",
            "published": dt.date(2021, 3, 1),
            "score": Decimal("1.500000000"),
            "updated_at": dt.datetime(2021, 3, 1, 10, 0),
        },
        {
            "year": 2022,
            "title": "Nextia",
            "published": dt.date(2022, 7, 15),
            "score": Decimal("0.000000001"),
            "updated_at": dt.datetime(2022, 7, 15, 23, 59, 59, 123456),
        },
        {
            "year": 2023,
            "title": None,
            "published": None,
            "score": None,
            "updated_at": None,
        },
    ]


def test_snapshot_describes_the_one_data_file(table: StaticTable) -> None:
    snapshot = table.current_snapshot()
    assert snapshot is not None
    assert snapshot.summary is not None
    assert snapshot.summary["total-records"] == "3"
    tasks = list(table.scan().plan_files())
    assert [task.file.file_path for task in tasks] == [
        "file:///iceberg_product/data/data.parquet"
    ]
    assert tasks[0].file.record_count == 3


def test_the_name_mapping_is_load_bearing(tmp_path: Path) -> None:
    """The same bytes minus ``schema.name-mapping.default``: the parquet has no field ids."""
    shutil.copytree(FIXTURES / "iceberg_product", tmp_path / "iceberg_product")
    metadata_file = tmp_path / "iceberg_product" / "metadata" / "v1.metadata.json"
    metadata = json.loads(metadata_file.read_text())
    del metadata["properties"]["schema.name-mapping.default"]
    metadata_file.write_text(json.dumps(metadata))

    unmapped = StaticTable.from_metadata(
        METADATA,
        {
            "py-io-impl": f"{__name__}.{FixtureFileIO.__name__}",
            "fixture-root": str(tmp_path),
        },
    )
    with pytest.raises(ValueError, match="field-ids"):
        unmapped.scan().to_arrow()


def test_pyiceberg_has_no_fileio_for_https() -> None:
    """Why the docs offer no PyIceberg recipe for a live Aran: its files are ``https://``."""
    location = "https://aran.example/api/iceberg/files/p/s/metadata/v1.metadata.json"
    with pytest.warns(UserWarning, match="No preferred file implementation for scheme: https"):
        io = load_file_io({}, location)
    with pytest.raises(ValueError, match="Unrecognized filesystem type in URI: https"):
        io.new_input(location).open()
