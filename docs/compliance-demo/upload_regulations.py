"""把银行/保险合规评测语料（19 篇）上传到 PaiSmart。

在仓库根目录执行：
    python docs/compliance-demo/upload_regulations.py
    python docs/compliance-demo/upload_regulations.py --check      # 只校验清单，不联网

清单来自 docs/compliance-demo/regulations-manifest.json：
- 14 篇官方原文（regulations/）+ 5 篇 DEMO 合成资料（synthetic/）；
- docType 必须填，ParseService 只在 docType 非空时才走 ComplianceClauseChunker 条款切分；
- S04 带 newVersionOf=S05，作为 S05 的新版本上传，脚本会自动先传 S05；
- R12 按 repealed=true 上传、S05 带 expiresAt，用来验证“废止/过期不参与检索”。

脚本从仓库根目录的 .env 读管理员账号，不打印任何密钥。运行结束打印一份回执，
逐篇给出 documentId / versionId / buildId 和实际生效的元数据，便于和评测集对账。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import date
from pathlib import Path

import requests

BASE_DIR = Path(__file__).resolve().parent
ROOT_DIR = BASE_DIR.parents[1]
API_BASE = "http://localhost:8081/api/v1"
MANIFEST = BASE_DIR / "regulations-manifest.json"


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    env_path = ROOT_DIR / ".env"
    if not env_path.exists():
        return values
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def load_manifest() -> list[dict]:
    documents = json.loads(MANIFEST.read_text(encoding="utf-8"))["documents"]
    required = ("id", "dir", "file", "docType", "jurisdiction")
    seen: set[str] = set()
    for item in documents:
        for key in required:
            if not item.get(key):
                raise SystemExit(f"清单缺少字段 {key}：{item}")
        if item["id"] in seen:
            raise SystemExit(f"清单 id 重复：{item['id']}")
        seen.add(item["id"])
        path = BASE_DIR / item["dir"] / item["file"]
        if not path.exists():
            raise SystemExit(f"清单引用的文件不存在：{path}")
    for item in documents:
        parent = item.get("newVersionOf")
        if parent and parent not in seen:
            raise SystemExit(f"{item['id']} 的 newVersionOf={parent} 不在清单里")
    return documents


def upload_order(documents: list[dict]) -> list[dict]:
    """被继承的版本先传，保证 newVersionOf 能拿到 documentId。"""
    ordered: list[dict] = []
    remaining = list(documents)
    known: set[str] = set()
    while remaining:
        progressed = False
        for item in list(remaining):
            parent = item.get("newVersionOf")
            if parent and parent not in known:
                continue
            ordered.append(item)
            remaining.remove(item)
            known.add(item["id"])
            progressed = True
        if not progressed:
            raise SystemExit("清单里的 newVersionOf 形成环或指向缺失文档")
    return ordered


def login(session: requests.Session, env: dict[str, str]) -> None:
    username = env.get("UPLOAD_USERNAME") or env.get("ADMIN_BOOTSTRAP_USERNAME")
    password = env.get("UPLOAD_PASSWORD") or env.get("ADMIN_BOOTSTRAP_PASSWORD")
    if not username or not password:
        raise SystemExit("没有在 .env 里找到 UPLOAD_PASSWORD / ADMIN_BOOTSTRAP_PASSWORD")
    response = session.post(API_BASE + "/users/login", json={"username": username, "password": password}, timeout=30)
    response.raise_for_status()
    session.headers.update({"Authorization": "Bearer " + response.json()["data"]["token"]})


def upload_one(session: requests.Session, item: dict, org_tag: str, document_id: str | None) -> dict:
    path = BASE_DIR / item["dir"] / item["file"]
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
    elif chunk.status_code == 200 and chunk.json().get("code") != 200:
        raise RuntimeError(f"{item['id']} 分片上传失败：{chunk.text[:300]}")

    payload = {
        "fileMd5": file_md5,
        "fileName": file_name,
        "docType": item["docType"],
        "jurisdiction": item["jurisdiction"],
        "issuer": item.get("issuer"),
        "applicableInstitutions": item.get("applicableInstitutions"),
        "effectiveAt": item.get("effectiveAt"),
        "expiresAt": item.get("expiresAt"),
        "repealed": bool(item.get("repealed")),
    }
    if document_id:
        payload["documentId"] = document_id

    merge = session.post(API_BASE + "/upload/merge", json=payload, timeout=180)
    merge.raise_for_status()
    body = merge.json()
    if body.get("code") != 200:
        raise RuntimeError(f"{item['id']} 合并失败：{json.dumps(body, ensure_ascii=False)[:300]}")
    data = body["data"]
    return {
        "id": item["id"],
        "file": file_name,
        "md5": file_md5,
        "documentId": data.get("documentId"),
        "versionId": data.get("versionId"),
        "buildId": data.get("buildId"),
        "newVersionOf": item.get("newVersionOf"),
    }


def verify(session: requests.Session, receipts: list[dict]) -> list[str]:
    """回读 GET /documents/uploads 的实际元数据，确认 docType 与有效期真的落库。

    只认接口返回的字段，不看上传时的请求参数：docType 为空说明条款切分不会生效，
    effectiveAt 对不上说明清单与库里不一致，两种情况都会让评测集与语料对不上账。
    """
    response = session.get(API_BASE + "/documents/uploads", timeout=120)
    if response.status_code != 200:
        return [f"回读文件列表失败 HTTP {response.status_code}"]
    listed = {}
    for row in response.json().get("data") or []:
        listed[str(row.get("fileName"))] = row

    problems: list[str] = []
    for receipt in receipts:
        row = listed.get(receipt["file"])
        if row is None:
            problems.append(f"{receipt['id']}: 文件列表里找不到 {receipt['file']}")
            continue
        if row.get("docType") != receipt["docType"]:
            problems.append(
                f"{receipt['id']}: docType 落库为 {row.get('docType')}，清单要求 {receipt['docType']}")
        if row.get("documentId") != receipt["documentId"]:
            problems.append(
                f"{receipt['id']}: documentId 落库为 {row.get('documentId')}，上传返回 {receipt['documentId']}")
        if row.get("effectiveAt") != receipt["effectiveAt"]:
            problems.append(
                f"{receipt['id']}: effectiveAt 落库为 {row.get('effectiveAt')}，清单要求 {receipt['effectiveAt']}")
        if row.get("expiresAt") != receipt["expiresAt"]:
            problems.append(
                f"{receipt['id']}: expiresAt 落库为 {row.get('expiresAt')}，清单要求 {receipt['expiresAt']}")
        if bool(row.get("repealed")) != receipt["repealed"]:
            problems.append(
                f"{receipt['id']}: repealed 落库为 {row.get('repealed')}，清单要求 {receipt['repealed']}")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description="上传银行/保险合规评测语料")
    parser.add_argument("--check", action="store_true", help="只校验清单与文件，不联网")
    args = parser.parse_args()

    documents = load_manifest()
    ordered = upload_order(documents)
    if args.check:
        print(f"清单校验通过：{len(ordered)} 篇，上传顺序：{', '.join(item['id'] for item in ordered)}")
        return

    env = load_env()
    org_tag = env.get("ADMIN_BOOTSTRAP_PRIMARY_ORG", "default")
    session = requests.Session()
    login(session, env)

    receipts: list[dict] = []
    document_ids: dict[str, str] = {}
    for item in ordered:
        parent_id = document_ids.get(item["newVersionOf"]) if item.get("newVersionOf") else None
        if item.get("newVersionOf") and not parent_id:
            raise SystemExit(f"{item['id']} 需要父文档 {item['newVersionOf']} 的 documentId，但父文档没有上传成功")
        receipt = upload_one(session, item, org_tag, parent_id)
        receipt["effectiveAt"] = item.get("effectiveAt")
        receipt["expiresAt"] = item.get("expiresAt")
        receipt["docType"] = item.get("docType")
        receipt["repealed"] = bool(item.get("repealed"))
        receipts.append(receipt)
        document_ids[item["id"]] = receipt["documentId"]
        role = f"（{item['newVersionOf']} 的新版本）" if item.get("newVersionOf") else ""
        print(f"{item['id']:>4} {item['file']:<52} documentId={receipt['documentId']} "
              f"versionId={receipt['versionId']} buildId={receipt['buildId']}{role}")

    (BASE_DIR / "upload-receipts.json").write_text(
        json.dumps({"uploadedAt": date.today().isoformat(), "receipts": receipts}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    print(f"\n上传完成 {len(receipts)} 篇，回执：{BASE_DIR / 'upload-receipts.json'}")

    problems = verify(session, receipts)
    if problems:
        print("\n回读校验发现问题：", file=sys.stderr)
        for line in problems:
            print("  - " + line, file=sys.stderr)
        raise SystemExit(1)
    print("回读校验通过：每篇都已落 docType 与有效期。")


if __name__ == "__main__":
    main()
