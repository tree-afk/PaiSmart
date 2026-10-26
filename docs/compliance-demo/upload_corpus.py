"""Upload the synthetic bank/insurance compliance demo corpus to PaiSmart.

Run from the repository root:
    python docs/compliance-demo/upload_corpus.py

The script reads the git-ignored root .env for admin credentials. It never
prints secrets. Edge-case documents are intentionally not uploaded.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import requests

BASE_DIR = Path(__file__).resolve().parent
ROOT_DIR = BASE_DIR.parents[1]
API_BASE = "http://localhost:8081/api/v1"


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    env_path = ROOT_DIR / ".env"
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def login(session: requests.Session, env: dict[str, str]) -> str:
    username = env.get("UPLOAD_USERNAME", "filemanager")
    password = env.get("UPLOAD_PASSWORD") or env["ADMIN_BOOTSTRAP_PASSWORD"]
    response = session.post(API_BASE + "/users/login", json={
        "username": username,
        "password": password,
    }, timeout=30)
    response.raise_for_status()
    token = response.json()["data"]["token"]
    session.headers.update({"Authorization": "Bearer " + token})
    return token


def upload_file(session: requests.Session, relative_path: str, metadata: dict,
                org_tag: str, document_id: str | None = None) -> dict:
    path = BASE_DIR / relative_path
    content = path.read_bytes()
    file_md5 = hashlib.md5(content).hexdigest()
    file_name = path.name

    chunk = session.post(API_BASE + "/upload/chunk", data={
        "fileMd5": file_md5,
        "chunkIndex": 0,
        "totalSize": len(content),
        "fileName": file_name,
        "totalChunks": 1,
        "orgTag": org_tag,
        "isPublic": "true",
    }, files={"file": (file_name, content, "text/markdown")}, timeout=120)
    if chunk.status_code not in (200, 409):
        chunk.raise_for_status()
    chunk_body = chunk.json()
    if chunk.status_code == 409:
        print(f"upload already exists, continue to merge: {relative_path}")
    elif chunk_body.get("code") != 200:
        raise RuntimeError(f"chunk upload failed for {relative_path}: {chunk_body}")

    payload = {
        "fileMd5": file_md5,
        "fileName": file_name,
        "docType": metadata["docType"],
        "jurisdiction": metadata["jurisdiction"],
        "issuer": metadata["issuer"],
        "applicableInstitutions": metadata["applicableInstitutions"],
        "effectiveAt": metadata["effectiveAt"],
        "expiresAt": metadata["expiresAt"],
        "repealed": metadata["repealed"],
    }
    if document_id:
        payload["documentId"] = document_id

    merge = session.post(API_BASE + "/upload/merge", json=payload, timeout=120)
    merge.raise_for_status()
    merge_body = merge.json()
    if merge_body.get("code") != 200:
        raise RuntimeError(f"merge failed for {relative_path}: {merge_body}")
    data = merge_body["data"]
    print(
        f"uploaded {relative_path}: documentId={data.get('documentId')} "
        f"versionNo={data.get('versionNo')} buildId={data.get('buildId')}"
    )
    return data


def main() -> None:
    manifest = json.loads((BASE_DIR / "manifest.json").read_text(encoding="utf-8"))
    env = load_env()
    org_tag = env.get("ADMIN_BOOTSTRAP_PRIMARY_ORG", "default")
    session = requests.Session()
    login(session, env)

    document_ids: dict[str, str] = {}
    for item in manifest["main"]:
        data = upload_file(session, item["file"], item, org_tag)
        document_ids[item["file"]] = data["documentId"]

    version = manifest["newVersion"]
    base_document_id = document_ids.get(version["baseFile"])
    if not base_document_id:
        raise RuntimeError("base document for new version was not uploaded: " + version["baseFile"])
    upload_file(session, version["file"], version, org_tag, base_document_id)

    print("\nDone. Uploaded main corpus and one future-effective new version.")
    print("Edge cases under docs/compliance-demo/edge-cases/ were not uploaded.")


if __name__ == "__main__":
    main()
