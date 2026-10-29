import json, os, pathlib
import requests
from dotenv import load_dotenv

load_dotenv()  # llegeix el fitxer .env de la carpeta actual

BASE = os.environ.get("ARAN_URL", "http://localhost:8080")
HEADERS = {"Authorization": f"Bearer {os.environ['ARAN_TOKEN']}"}

OUT = pathlib.Path("i1_responses")
OUT.mkdir(exist_ok=True)

def get(path, name):
    r = requests.get(BASE + path, headers=HEADERS, timeout=60)
    print(path, "->", r.status_code)
    try:
        data = r.json()
    except ValueError:
        data = {"raw": r.text}
    (OUT / f"{name}.json").write_text(
        json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    return data

versions = get("/api/schema/versions", "schema_versions")
projects = get("/api/projects", "projects")

active = next(v for v in versions if v["status"] == "ACTIVE")
pid = projects[0]["id"]

get(f"/api/graph/schema/{active['id']}", "graph_schema_active")
get(f"/api/projects/{pid}/model", "model")
get("/api/operators", "operators")

repos = get(f"/api/projects/{pid}/repositories", "repositories")
for repo in repos:
    rid = repo["id"]
    datasets = get(f"/api/projects/{pid}/repositories/{rid}/datasets", f"datasets_{rid[:8]}")
    for ds in datasets:
        get(f"/api/graph/source/{ds['id']}", f"graph_source_{ds['name']}")
        get(f"/api/projects/{pid}/repositories/{rid}/datasets/{ds['id']}/profiles",
            f"profiles_{ds['name']}")

def post(path, body, name):
    r = requests.post(BASE + path, headers=HEADERS, json=body, timeout=120)
    print(path, body.get("operation"), "->", r.status_code)
    try:
        data = r.json()
    except ValueError:
        data = {"raw": r.text}
    (OUT / f"{name}.json").write_text(
        json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    return data

QE = "/api/operators/query-engine/invoke"
post(QE, {"operation": "catalog", "parameters": {}}, "qe_catalog")
post(QE, {"operation": "cube-evidence", "parameters": {}}, "qe_cube_evidence")
post(QE, {"operation": "explain", "parameters": {}}, "qe_explain")
post(QE, {"operation": "run-oql", "parameters": {
    "oql": "SELECT COUNT(*) AS n FROM IPaper"}}, "qe_count_ipaper")
post(QE, {"operation": "run-oql", "parameters": {
    "oql": "SELECT COUNT(*) AS n FROM IPaper -[hasAuthor]-> Int_researchers"}}, "qe_count_join")
post(QE, {"operation": "run-oql", "parameters": {
    "oql": "SELECT IPaper.title, Int_researchers.full_name FROM IPaper -[hasAuthor]-> Int_researchers",
    "limit": "5"}}, "qe_join_sample")