"""构建银行/保险合规 50 题检索评测集（bank-compliance-v1）。

规格表在 CASES 里；脚本负责校验并产出三份文件：

1. src/main/resources/eval/bank-compliance-v1.jsonl   —— 评测集本体（5 个标准字段）
2. docs/eval/bank-compliance-v1-gold.json             —— 逐题台账（机器可读）
3. docs/eval/bank-compliance-v1-gold.md               —— 逐题台账（人工阅读）

校验项：
- expectedEvidence 必须是原文里逐字出现的短语（NFKC + 去空白 + 小写后做包含判断）；
- 该短语必须落在同一个「第 X 条」块内，避免被 ComplianceClauseChunker 切到两个分块；
- expectedFileNames 里的文件必须存在，且按 asOf 复算为“当前可检索”（未废止、未过失效日、已生效）；
- 短语在同一篇原文里重复出现时给出提示（模板要求是“唯一短语”）。

用法：
    python docs/eval/tools/build_bank_compliance_eval.py
"""

from __future__ import annotations

import json
import re
import unicodedata
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
REG_DIR = ROOT / "docs" / "compliance-demo" / "regulations"
SYN_DIR = ROOT / "docs" / "compliance-demo" / "synthetic"
OUT_JSONL = ROOT / "src" / "main" / "resources" / "eval" / "bank-compliance-v1.jsonl"
OUT_GOLD_JSON = ROOT / "docs" / "eval" / "bank-compliance-v1-gold.json"
OUT_GOLD_MD = ROOT / "docs" / "eval" / "bank-compliance-v1-gold.md"

AS_OF = date(2026, 11, 6)

# 语料：id -> (目录, 文件名)。R 为官方原文，S 为演示用合成资料。
DOCS: dict[str, tuple[Path, str]] = {
    "R01": (REG_DIR, "R01-data-security-2024.md"),
    "R02": (REG_DIR, "R02-insurance-sales-2023.md"),
    "R03": (REG_DIR, "R03-consumer-protection-2022.md"),
    "R04": (REG_DIR, "R04-pbc-consumer-protection-2020.md"),
    "R05": (REG_DIR, "R05-internet-insurance-2020.md"),
    "R06": (REG_DIR, "R06-operational-risk-2023.md"),
    "R07": (REG_DIR, "R07-shanghai-sales-recording-2020.md"),
    "R08": (REG_DIR, "R08-shanghai-medical-insurance-2022.md"),
    "R09": (REG_DIR, "R09-internet-loan-2021.md"),
    "R10": (REG_DIR, "R10-internet-loan-2022.md"),
    "R11": (REG_DIR, "R11-internet-insurance-recording-2020.md"),
    "R12": (REG_DIR, "R12-internet-insurance-interim-2015.md"),
    "R13": (REG_DIR, "R13-xinjiang-sales-recording-2026.md"),
    "R14": (REG_DIR, "R14-chongqing-internet-loan-2026.md"),
    "S01": (SYN_DIR, "S01-internal-data-security-demo.md"),
    "S02": (SYN_DIR, "S02-internal-shanghai-sales-recording-demo.md"),
    "S03": (SYN_DIR, "S03-internal-chongqing-internet-loan-demo.md"),
    "S04": (SYN_DIR, "S04-product-critical-illness-2026-demo.md"),
    "S05": (SYN_DIR, "S05-product-critical-illness-2025-demo.md"),
}

ARTICLE_RE = re.compile(r"^(第[0-9０-９一二三四五六七八九十百千万亿零〇两]+条)")
CHAPTER_RE = re.compile(r"^(第[0-9０-９一二三四五六七八九十百千万亿零〇两]+章)(.*)$")
SECTION_RE = re.compile(r"^(第[0-9０-９一二三四五六七八九十百千万亿零〇两]+节)(.*)$")

CATEGORY_LABEL = {
    "fact": "条款事实",
    "multi": "跨文档综合",
    "region": "地区适用",
    "version": "版本/当前可检索",
    "conflict": "冲突条款",
    "noanswer": "无答案/应拒答",
    "clarify": "缺用户条件/应澄清",
    "citation": "条款引用",
}


def normalize(text: str) -> str:
    if text is None:
        return ""
    return unicodedata.normalize("NFKC", text).replace("\n", "").replace("\r", "").replace(" ", "").replace("\t", "").lower()


def load_metadata() -> dict[str, dict]:
    """把两份台账合并成 id -> 统一字段。"""
    meta: dict[str, dict] = {}
    for entry in json.loads((REG_DIR / "sources.json").read_text(encoding="utf-8")):
        meta[entry["id"]] = {
            "file": entry["file"],
            "title": entry["title"],
            "docType": entry["docType"],
            "jurisdiction": entry["jurisdiction"],
            "issuer": entry["issuer"],
            "documentNo": entry.get("documentNo"),
            "effectiveAt": entry.get("effectiveDate"),
            "expiresAt": None,
            "repealed": entry.get("status") == "已废止",
            "status": entry.get("status"),
            "clauseUnit": entry.get("clauseUnit"),
        }
    for entry in json.loads((SYN_DIR / "synthetic_sources.json").read_text(encoding="utf-8"))["documents"]:
        doc_id = entry["file"][:3]
        meta[doc_id] = {
            "file": entry["file"],
            "title": entry["title"],
            "docType": entry["docType"],
            "jurisdiction": entry["jurisdiction"],
            "issuer": entry["issuer"],
            "documentNo": None,
            "effectiveAt": entry.get("effectiveAt"),
            "expiresAt": entry.get("expiresAt"),
            "repealed": bool(entry.get("repealed")),
            "status": "演示合成",
            "clauseUnit": "条",
        }
    return meta


def read_doc(doc_id: str) -> str:
    directory, name = DOCS[doc_id]
    return (directory / name).read_text(encoding="utf-8")


def split_blocks(text: str) -> list[dict]:
    """按行首「第 X 条」切块，并记录所属章/节；无条标记的文档整篇算一块。"""
    blocks: list[dict] = []
    chapter = None
    section = None
    marker = "全文"
    buf: list[str] = []

    def flush() -> None:
        nonlocal buf
        body = "\n".join(buf).strip()
        if body:
            blocks.append({"marker": marker, "path": build_path(chapter, section, marker), "text": body})
        buf = []

    for line in text.splitlines():
        stripped = line.strip()
        chapter_match = CHAPTER_RE.match(stripped)
        if chapter_match:
            flush()
            chapter = normalize_heading(chapter_match.group(1), chapter_match.group(2))
            section = None
            continue
        section_match = SECTION_RE.match(stripped)
        if section_match:
            flush()
            section = normalize_heading(section_match.group(1), section_match.group(2))
            continue
        article_match = ARTICLE_RE.match(stripped)
        if article_match:
            flush()
            marker = article_match.group(1)
            buf = [stripped]
            continue
        buf.append(stripped)
    flush()
    return blocks


def normalize_heading(marker: str, rest: str | None) -> str:
    title = (rest or "").strip().replace("\n", " ")
    title = re.sub(r"\s+", " ", title)
    title = re.sub(r"^[、.．:：\s]+", "", title)
    return marker if not title else marker + " " + title


def build_path(chapter: str | None, section: str | None, marker: str) -> str:
    parts = [part for part in (chapter, section, None if marker == "全文" else marker) if part]
    return " > ".join(parts) if parts else "全文"


def retrievable(meta: dict) -> tuple[bool, str]:
    if meta["repealed"]:
        return False, "已废止"
    effective = meta.get("effectiveAt")
    if effective and date.fromisoformat(effective) > AS_OF:
        return False, "尚未生效"
    expires = meta.get("expiresAt")
    if expires and date.fromisoformat(expires) < AS_OF:
        return False, "已过失效日期"
    return True, "当前可检索"


def case(case_id: str, category: str, question: str, answer: str,
         evidence: list[tuple[str, str]] | None = None, tier: str = "", note: str = "",
         expected_behavior: str = "") -> dict:
    return {
        "id": case_id,
        "category": category,
        "question": question,
        "answer": answer,
        "evidence": list(evidence or []),
        "tier": tier,
        "note": note,
        "expectedBehavior": expected_behavior,
    }


CASES: list[dict] = [
    # ---------------- fact 12：单条款事实 ----------------
    case("fact-01-data-security-event-report", "fact",
         "银行保险机构发生数据安全事件后，应在多长时间内向监管机构报告，多长时间内提交正式书面报告？",
         "依据《银行保险机构数据安全管理办法》第六十九条：数据安全事件发生 2 小时内向国家金融监督管理总局或其派出机构报告，事件发生后 24 小时内提交正式书面报告；处置期间每 2 小时上报进展，处置结束后 5 个工作日内报送评估、总结和改进报告。",
         [("R01", "数据安全事件发生2小时内，银行保险机构应当向国家金融监督管理总局或者其派出机构报告")],
         tier="原文"),
    case("fact-02-data-security-annual-report", "fact",
         "银行保险机构应在每年什么时间前报送上一年度数据安全风险评估报告？",
         "每年 1 月 15 日前向国家金融监督管理总局或其派出机构报送（《银行保险机构数据安全管理办法》第七十四条）。",
         [("R01", "每年1月15日前向国家金融监督管理总局或者其派出机构报送上一年度数据安全风险评估报告")],
         tier="原文"),
    case("fact-03-data-security-penalty", "fact",
         "银行业金融机构违反《银行保险机构数据安全管理办法》会被处以多大金额的罚款？",
         "依据第七十七条：责令改正，并处以二十万元以上五十万元以下罚款；情节特别严重或逾期不改正的，可责令停业整顿或吊销经营许可证；对直接负责的董事、高级管理人员和其他直接责任人员可处五万元以上五十万元以下罚款。",
         [("R01", "责令银行业金融机构改正，并处以二十万以上五十万以下罚款")],
         tier="原文"),
    case("fact-04-sales-staff-leave-notice", "fact",
         "保险销售人员离职后，对其在职期间签订、尚未履行完毕的一年期以上人身保险合同，保险公司应在多长时间内通知投保人？",
         "依据《保险销售行为管理办法》第三十六条：应在该销售人员离职手续办理完成后的 30 日内，明确通知投保人或被保险人其离职信息、保险合同状况及获得后续服务的途径。",
         [("R02", "在该保险销售人员的离职手续办理完成后的30日内明确通知投保人或者被保险人有关该保险销售人员的离职信息")],
         tier="原文"),
    case("fact-05-consumer-protection-audit-cycle", "fact",
         "银行保险机构消费者权益保护内部审计以几年为一个周期？",
         "依据《银行保险机构消费者权益保护管理办法》第十九条：以 5 年为一个周期，全面覆盖本机构相关部门和一级分支机构。",
         [("R03", "以5年为一个周期全面覆盖本机构相关部门和一级分支机构")],
         tier="原文"),
    case("fact-06-consumer-protection-fine", "fact",
         "银行保险机构严重侵害消费者合法权益，监管机构可对董事会成员和高级管理人员处以多少罚款？",
         "依据《银行保险机构消费者权益保护管理办法》第五十四条：可给予警告，并处以 10 万元以下罚款；机构层面另有通报批评、警告、10 万元以下罚款等处罚。",
         [("R03", "并处以10万元以下罚款")],
         tier="原文"),
    case("fact-07-pbc-complaint-handling-days", "fact",
         "中国人民银行分支机构收到金融消费者投诉后，应在多少个工作日内作出是否接收投诉等处理？",
         "依据《中国人民银行金融消费者权益保护实施办法》第四十三条：自收到投诉之日起 7 个工作日内完成登记、决定是否接收并告知投诉人；决定接收的转交被投诉机构或调解组织。",
         [("R04", "应当自收到投诉之日起7个工作日内作出下列处理")],
         tier="原文"),
    case("fact-08-pbc-self-assessment-report", "fact",
         "银行、支付机构应在每年什么时间前向中国人民银行或其分支机构报送金融消费者权益保护自评估报告？",
         "依据《中国人民银行金融消费者权益保护实施办法》第五十六条：按年度自评估，并于次年 1 月 31 日前报送。",
         [("R04", "并于次年1月31日前向中国人民银行或其分支机构报送自评估报告")],
         tier="原文"),
    case("fact-09-internet-insurance-suspend-notice", "fact",
         "保险机构拟自行停止自营网络平台的互联网保险业务经营，应提前多少个工作日公告？",
         "依据《互联网保险业务监管办法》第八条：应至少提前 20 个工作日，在官方网站和自营网络平台发布公告；涉及债权债务处置的一并公告。",
         [("R05", "应至少提前20个工作日在官方网站和自营网络平台发布公告")],
         tier="原文"),
    case("fact-10-internet-insurance-annual-report", "fact",
         "保险机构应在每年什么时间前报送上一年度互联网保险业务经营情况报告？",
         "依据《互联网保险业务监管办法》第七十四条：每年 4 月 30 日前报送至互联网保险监管相关信息系统，报告由总经理和互联网保险业务负责人签字。",
         [("R05", "保险机构应于每年4月30日前向互联网保险监管相关信息系统报送上一年度互联网保险业务经营情况报告")],
         tier="原文"),
    case("fact-11-operational-risk-report-days", "fact",
         "银行保险机构应在知悉重大操作风险事件后几个工作日内向监管机构报告？",
         "依据《银行保险机构操作风险管理办法》第四十二条：在知悉或应当知悉重大操作风险事件后 5 个工作日内，按监管职责归属向国家金融监督管理总局或其派出机构报告。",
         [("R06", "银行保险机构应当在知悉或者应当知悉以下重大操作风险事件5个工作日内")],
         tier="原文"),
    case("fact-12-complaint-records-retention", "fact",
         "银行、支付机构的投诉资料应自投诉办结之日起至少留存多久？",
         "依据《中国人民银行金融消费者权益保护实施办法》第四十四条：投诉资料留存时间自投诉办结之日起不得少于 3 年，法律、行政法规另有规定的从其规定。",
         [("R04", "投诉资料留存时间自投诉办结之日起不得少于3年")],
         tier="原文"),

    # ---------------- multi 8：跨文档综合 ----------------
    case("multi-01-incident-report-deadlines", "multi",
         "银行保险机构发生数据安全事件、重大操作风险事件，分别应在多长时间内向监管机构报告？",
         "两类事件时限不同：数据安全事件按《银行保险机构数据安全管理办法》第六十九条，2 小时内报告、24 小时内提交正式书面报告；重大操作风险事件按《银行保险机构操作风险管理办法》第四十二条，知悉或应当知悉后 5 个工作日内报告。",
         [("R01", "数据安全事件发生2小时内，银行保险机构应当向国家金融监督管理总局或者其派出机构报告"),
          ("R06", "银行保险机构应当在知悉或者应当知悉以下重大操作风险事件5个工作日内")],
         tier="原文"),
    case("multi-02-recording-retention", "multi",
         "上海地区人身保险线下“双录”视听资料，与全国互联网保险销售行为可回溯资料，保存期限分别怎么规定？纠纷情况下各自延到什么时候？",
         "上海通知第六部分要求：自保险合同终止之日起算，保险期间一年及以下不少于五年、超过一年不少于十年；遇投诉、诉讼等纠纷至少保存至纠纷结束后二年。互联网保险可回溯通知第十九条要求：保险期间一年以下不少于五年、超过一年不少于十年；纠纷至少保存至纠纷结束后三年。差异在纠纷后的留存年限：上海 2 年、互联网可回溯 3 年。",
         [("R07", "保险期间在一年及以下的不得少于五年，保险期间超过一年的不得少于十年"),
          ("R11", "遇消费者投诉、法律诉讼等纠纷的，应当至少保存至纠纷结束后三年")],
         tier="原文"),
    case("multi-03-internet-loan-cooperation", "multi",
         "商业银行与合作机构共同出资发放互联网贷款，单笔贷款合作方出资比例下限是多少？重庆辖内的机构还要满足哪些额外要求？",
         "出资比例下限见《中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知》第二条：单笔贷款中合作方出资比例不得低于 30%。重庆金融监管局《关于进一步加强辖内银行业金融机构互联网贷款业务风险管理的通知》（渝金管规〔2026〕2号）对辖内机构另提出八方面要求：一、切实转变经营导向，将互联网助贷、联合贷及第三方平台获客贷款统一纳入全行风险偏好、资本约束和年度信贷计划，不得作为弥补线下投放不足、维持短期营收或扩大客户数量的工具；二、提升自主获客能力，减少对互联网平台的路径依赖，健全自主获客、自主风控和贷后管理体系；三、强化业务风险管理，多维数据交叉验证，识别多头共债和过度负债，不得因担保、增信放松客户准入，加强风险模型全生命周期管理；四、强化合作机构管理，审慎制定准入标准，合理设置担保增信类业务规模和集中度限额，完善退出机制，服务费合理定价；五、强化还款资金管理，防范平台截留、挪用还款资金，防止形成资金池；六、提高贷后管理主动性，及时修订合作协议，排查合作机构催收行为合规性；七、强化宣传教育引导，明示个人贷款综合融资成本，畅通投诉渠道，不得直接将投诉纠纷转合作方处理；八、加强监督检查问责，主动自查自纠，自通知印发之日起三个月内向我局报送专项排查报告。",
         [("R09", "单笔贷款中合作方出资比例不得低于30%"),
          ("R14", "自通知印发之日起三个月内向我局报送专项排查报告")],
         tier="原文"),
    case("multi-04-staff-notice-and-annual-report", "multi",
         "保险销售人员离职的通知义务，与互联网保险业务经营情况年度报告，分别有哪些时限要求？",
         "《保险销售行为管理办法》第三十六条：离职手续办理完成后 30 日内通知投保人或被保险人；《互联网保险业务监管办法》第七十四条：每年 4 月 30 日前报送上一年度互联网保险业务经营情况报告。",
         [("R02", "在该保险销售人员的离职手续办理完成后的30日内明确通知投保人或者被保险人"),
          ("R05", "每年4月30日前向互联网保险监管相关信息系统报送上一年度互联网保险业务经营情况报告")],
         tier="原文"),
    case("multi-05-audit-cycle-and-complaint", "multi",
         "消费者权益保护内部审计周期，与中国人民银行分支机构转交投诉的处理时限，分别是多少？",
         "《银行保险机构消费者权益保护管理办法》第十九条：内部审计以 5 年为一个周期全面覆盖；《中国人民银行金融消费者权益保护实施办法》第四十三条：人民银行分支机构 7 个工作日内作出处理，银行、支付机构收到转交投诉后 15 日内答复，最长不超过 60 日。",
         [("R03", "以5年为一个周期全面覆盖本机构相关部门和一级分支机构"),
          ("R04", "应当自收到投诉之日起7个工作日内作出下列处理")],
         tier="原文"),
    case("multi-06-rectification-deadlines", "multi",
         "操作风险管理办法对不同规模机构的执行期限怎么安排？互联网保险办法的整改期限又是多久？",
         "《银行保险机构操作风险管理办法》第五十条：关于第二、三、四章的规定，规模较大的保险机构自施行之日起 1 年内执行，规模较小的银行保险机构 2 年内执行。《互联网保险业务监管办法》第八十一条：施行之日起 3 个月内完成制度建设和营销宣传、销售管理、信息披露等问题整改，6 个月内完成业务和经营等其他问题整改，12 个月内完成自营网络平台网络安全等级保护认证。",
         [("R06", "规模较大的保险机构自本办法施行之日起1年内执行"),
          ("R05", "3个月内完成制度建设、营销宣传、销售管理、信息披露等问题整改")],
         tier="原文"),
    case("multi-07-quality-check-ratio-two-regions", "multi",
         "上海和新疆对人身保险“双录”的质检抽查比例分别有什么要求？",
         "上海通知第四部分：对全部成交件视听资料按每月不低于 30% 的比例在犹豫期内全程质检。新疆通知第四部分：每月质检抽样比例不得低于当月承保的应录音录像保单件数的 30%，其中投保人为特殊人群的保单每月质检覆盖率不得低于 60%，销售投连险产品或 60 周岁（含）以上投保人的“双录”视听资料应 100% 质检。",
         [("R07", "按每月不低于30%的比例在犹豫期内全程质检"),
          ("R13", "针对投保人为特殊人群的录音录像保单，每月质检覆盖率不得低于60%")],
         tier="原文"),
    case("multi-08-medical-account-and-complaint", "multi",
         "上海医保个人账户购买商业健康保险时退保金应退到哪里？央行对投诉资料留存有什么要求？",
         "《上海市医保个人账户购买商业健康保险业务管理办法》第十一条：使用医保个人账户结余资金购买并退保的，退保金必须退回投保人本人的医保个人账户。《中国人民银行金融消费者权益保护实施办法》第四十四条：投诉资料留存时间自投诉办结之日起不得少于 3 年。",
         [("R08", "退保金必须退回投保人本人的医保个人账户"),
          ("R04", "投诉资料留存时间自投诉办结之日起不得少于3年")],
         tier="原文"),

    # ---------------- region 6：地区适用 ----------------
    case("region-01-shanghai-recording-scope", "region",
         "上海地区人身保险销售“双录”的可回溯要求，适用于哪些销售渠道？哪些渠道被排除？",
         "适用《上海银保监局关于深入推进人身保险销售行为可回溯管理的通知》第一部分：除电话销售业务和互联网保险业务外，在上海地区销售人身保险产品的保险公司和保险中介机构，向自然人销售保险期间超过一年（含不超过一年但保证续保）的产品应实施“双录”；团体保险及职工医保个人账户购买商业健康保险等政策性保险产品除外。",
         [("R07", "除电话销售业务和互联网保险业务外，在上海地区销售人身保险产品的保险公司和保险中介机构")],
         tier="原文"),
    case("region-02-shanghai-identity-verification", "region",
         "上海医保个人账户购买商业健康保险，保险公司应如何验证投保行为？",
         "依据《上海市医保个人账户购买商业健康保险业务管理办法》第九条：应采取有效手段对投保人身份进行验证，确保投保行为系医保个人账户持有人本人的真实意愿。",
         [("R08", "确保投保行为系医保个人账户持有人本人的真实意愿")],
         tier="原文"),
    case("region-03-shanghai-policy-loan", "region",
         "上海医保个人账户购买商业健康保险，能否办理保单贷款？",
         "不能。依据《上海市医保个人账户购买商业健康保险业务管理办法》第十三条：保险公司不得提供医保个人账户购买商业健康保险产品的保单贷款服务。",
         [("R08", "保险公司不得提供医保个人账户购买商业健康保险产品的保单贷款服务")],
         tier="原文"),
    case("region-04-xinjiang-special-group-qc", "region",
         "新疆对特殊人群“双录”保单的质检覆盖率有什么特别要求？",
         "依据新疆通知第四部分：每月质检抽样比例不得低于当月承保的应录音录像保单件数的 30%；针对投保人为特殊人群的录音录像保单，每月质检覆盖率不得低于 60%，且质检应在犹豫期内完成。",
         [("R13", "针对投保人为特殊人群的录音录像保单，每月质检覆盖率不得低于60%")],
         tier="原文"),
    case("region-05-chongqing-special-report", "region",
         "重庆辖内银行业金融机构应在多长时间内向重庆金融监管局报送专项排查报告？",
         "依据重庆金融监管局通知第八条：自通知印发之日起三个月内报送专项排查报告。",
         [("R14", "自通知印发之日起三个月内向我局报送专项排查报告")],
         tier="原文"),
    case("region-06-xinjiang-agent-feedback", "region",
         "新疆辖内银行类保险兼业代理机构自行质检后，应在多长时间内向合作保险公司反馈质检结果？",
         "依据新疆通知第四部分：银行类保险兼业代理机构自存视听资料且未向保险公司提供的，应自行开展质检，并在质检完成后的 10 个工作日内将全部质检结果书面反馈至合作的保险公司。",
         [("R13", "并在质检完成后的10个工作日内，将全部质检结果书面反馈至合作的保险公司")],
         tier="原文"),

    # ---------------- version 6：版本/当前可检索 ----------------
    case("version-01-internet-insurance-effective", "version",
         "现行《互联网保险业务监管办法》何时施行？它废止了哪部旧办法？",
         "《互联网保险业务监管办法》第八十二条：自 2021 年 2 月 1 日起施行，《互联网保险业务监管暂行办法》（保监发〔2015〕69 号）同时废止。因此 2015 年暂行办法已不可作为现行依据。",
         [("R05", "本办法自2021年2月1日起施行，《互联网保险业务监管暂行办法》（保监发〔2015〕69号）同时废止")],
         tier="原文"),
    case("version-02-internet-loan-effective", "version",
         "互联网贷款合作方出资比例不低于 30%、以及跨地域经营的要求，从什么时间开始执行？",
         "依据《中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知》第六条：第二条（出资比例不低于 30%）和第五条（地方法人银行不得跨注册地辖区开展业务）自 2022 年 1 月 1 日起执行，存量业务自然结清。",
         [("R09", "本通知第二条、第五条自2022年1月1日起执行")],
         tier="原文"),
    case("version-03-internet-loan-transition", "version",
         "商业银行互联网贷款的存量业务过渡期截止到什么时候？",
         "依据 2022 年《关于加强商业银行互联网贷款业务管理 提升金融服务质效的通知》第八条：存量业务过渡期至 2023 年 6 月 30 日；过渡期内新增业务应符合《办法》《互联网贷款通知》和本通知要求。",
         [("R10", "商业银行互联网贷款存量业务过渡期至2023年6月30日")],
         tier="原文"),
    case("version-04-operational-risk-execution", "version",
         "《银行保险机构操作风险管理办法》对规模较大和规模较小的机构分别规定了多长的执行期限？",
         "依据第五十条：第二、三、四章的规定，规模较大的保险机构自本办法施行之日起 1 年内执行，规模较小的银行保险机构自施行之日起 2 年内执行。",
         [("R06", "规模较大的保险机构自本办法施行之日起1年内执行")],
         tier="原文"),
    case("version-05-shanghai-recording-phases", "version",
         "上海人身保险销售行为可回溯管理从什么时候正式实施？过渡期怎么安排？",
         "依据上海通知第十部分：第一阶段 2021 年 1 月 1 日至 2021 年 3 月 31 日为实施过渡期，各机构选择至少一类在售产品试点；第二阶段 2021 年 4 月 1 日开始正式实施，纳入日常监管。",
         [("R07", "第二阶段：2021年4月1日开始，正式实施")],
         tier="原文"),
    case("version-06-critical-illness-version", "version",
         "演示安康重大疾病保险 2025 版的等待期和犹豫期是多少天？现在能按 2025 版回答吗？",
         "知识库中 2025 版条款已过失效日期（2026 年 1 月 1 日停止适用），不可检索，因此无法确认其等待期、犹豫期的具体天数。现行 2026 版（2026 年 1 月 1 日起适用）第四条为等待期 90 日、第八条为犹豫期 15 日。",
         [("S04", "自本合同生效之日起90日为等待期")],
         tier="合成"),
    # ---------------- conflict 5：冲突条款 ----------------
    case("conflict-01-data-security-event-deadline", "conflict",
         "本行数据安全管理办法规定数据安全事件 4 小时内报告、48 小时内提交书面报告，这与监管规定一致吗？",
         "不一致。《银行保险机构数据安全管理办法》第六十九条要求 2 小时内报告、24 小时内提交正式书面报告、每 2 小时上报进展；本行制度（S01 第七条、第八条）的 4 小时／48 小时低于监管要求，应以监管规定为准并修订内部制度。",
         [("R01", "数据安全事件发生2小时内，银行保险机构应当向国家金融监督管理总局或者其派出机构报告"),
          ("S01", "本行应当在4小时内向国家金融监督管理总局及其派出机构报告")],
         tier="原文+合成"),
    case("conflict-02-quality-check-ratio", "conflict",
         "本行上海分行细则规定双录音视频每月质检不低于 10%，上海监管要求是多少？该细则合规吗？",
         "不合规。上海通知第四部分第（一）项要求对全部成交件视听资料按每月不低于 30% 的比例在犹豫期内全程质检；本行上海分行细则（S02 第五条）只要求每月不低于 10%，低于监管要求，应修订。",
         [("R07", "按每月不低于30%的比例在犹豫期内全程质检"),
          ("S02", "按每月不低于10%的比例在犹豫期内全程质检")],
         tier="原文+合成"),
    case("conflict-03-rectification-deadline", "conflict",
         "本行上海分行细则规定质检问题 20 个工作日内整改，上海监管要求是多久？",
         "上海通知第四部分第（二）项要求自发现问题之日起 15 个工作日内整改；本行上海分行细则（S02 第六条）规定 20 个工作日内完成整改，长于监管要求，应以监管要求为准并修订。",
         [("R07", "应当自发现问题之日起15个工作日内整改"),
          ("S02", "自发现问题之日起20个工作日内完成整改")],
         tier="原文+合成"),
    case("conflict-04-cooperation-funding-ratio", "conflict",
         "本行重庆分行细则规定合作方出资比例不得低于 20%，与全国监管要求是否一致？",
         "不一致。《中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知》第二条要求单笔贷款中合作方出资比例不得低于 30%；本行重庆分行细则（S03 第四条）规定 20%，低于监管下限，应修订。",
         [("R09", "单笔贷款中合作方出资比例不得低于30%"),
          ("S03", "单笔贷款中合作方出资比例不得低于20%")],
         tier="原文+合成"),
    case("conflict-05-concentration-limits", "conflict",
         "本行重庆分行细则规定单一合作方集中度 35%、全部合作机构 60%，与全国监管要求相比如何？",
         "两个指标都突破了监管上限。《中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知》第三条：与单一合作方（含其关联方）发放的本行贷款余额不得超过本行一级资本净额的 25%；第四条：与全部合作机构共同出资发放的贷款余额不得超过本行全部贷款余额的 50%。本行细则（S03 第五、六条）为 35% 和 60%，均应修订。",
         [("R09", "与单一合作方（含其关联方）发放的本行贷款余额不得超过本行一级资本净额的25%"),
          ("S03", "不得超过本行一级资本净额的35%")],
         tier="原文+合成"),

    # ---------------- noanswer 6：无答案/应拒答 ----------------
    case("noanswer-01-it-outsourcing-rule", "noanswer",
         "《银行保险机构信息科技外包风险监管办法》（银保监办发〔2022〕118 号）对信息科技外包提出了哪些具体要求？",
         "知识库未收录该办法，无法回答其具体要求。现有语料只覆盖数据安全、操作风险、消费者权益保护、互联网保险与互联网贷款等办法，不能据此推断信息科技外包的规定。",
         tier="原文"),
    case("noanswer-02-guarantee-fee-cap", "noanswer",
         "商业银行互联网贷款合作机构收取的担保费率上限是多少？",
         "知识库没有规定担保费率上限。《中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知》只规定出资比例与集中度；重庆通知要求支付服务费合理定价、质价相符，但未给出费率上限。因此无法确认具体数值。",
         tier="原文"),
    case("noanswer-03-xinjiang-internet-loan", "noanswer",
         "新疆地区对商业银行互联网贷款合作方出资比例有什么地方性要求？",
         "知识库中没有新疆地区互联网贷款的规定。新疆语料仅涉及人身保险销售行为可回溯管理，出资比例要求只能引用全国性文件（不得低于 30%），不能据此认为新疆另有更严格或更宽松的地方标准。",
         tier="原文"),
    case("noanswer-04-tax-deferred-pension", "noanswer",
         "个人税收递延型养老保险的税前扣除限额是多少？",
         "知识库未收录该险种的税收政策，无法回答扣除限额。语料中只在可回溯管理的适用范围里提到该险种属于除外情形，不涉及税收扣除标准。",
         tier="原文"),
    case("noanswer-05-2027-budget", "noanswer",
         "本行 2027 年度合规培训预算是多少？",
         "知识库没有该预算信息，无法回答。内部制度类语料只覆盖数据安全事件报告、销售可回溯、互联网贷款合作机构管理等条款，不含年度预算。",
         tier="原文"),
    case("noanswer-06-critical-illness-disease-list", "noanswer",
         "演示安康重大疾病保险 2026 版具体承保哪些重大疾病？请列出病种清单。",
         "知识库无法列明病种清单。条款第三条只说明保险责任包括重大疾病保险金、轻症疾病保险金和身故保险金，具体疾病定义以条款释义为准，语料未提供释义或病种清单。",
         tier="合成"),

    # ---------------- clarify 4：缺用户条件/应澄清 ----------------
    case("clarify-01-quality-check-ratio", "clarify",
         "双录的质检比例是多少？",
         "需要先确认地区和业务类型：上海地区线下人身保险按每月不低于 30% 全程质检；新疆为每月不低于 30%，特殊人群保单 60%、投连险或 60 周岁以上投保人 100%；互联网保险销售行为可回溯适用另一套规定。缺条件时不应直接给单一结论。",
         tier="原文"),
    case("clarify-02-waiting-period", "clarify",
         "等待期是多少天？",
         "需要先确认产品和版本。演示安康重大疾病保险现行 2026 版等待期为 90 日；2025 版已停止适用且不可检索，不能凭 2026 版推断其数值。健康保险产品的观察期起算与影响另有展示要求。",
         tier="合成", expected_behavior="clarify_first"),
    case("clarify-03-complaint-deadline", "clarify",
         "投诉要在多长时间内处理完？",
         "需要先确认投诉渠道和主体：中国人民银行分支机构 7 个工作日内作出处理决定，银行、支付机构收到转交投诉后 15 日内答复、最长不超过 60 日；保险机构自有渠道的投诉处理时限另有规定。",
         tier="原文"),
    case("clarify-04-funding-ratio", "clarify",
         "出资比例的下限是多少？",
         "需要先确认是全国监管要求还是本行内部制度：全国性互联网贷款要求单笔贷款中合作方出资比例不得低于 30%；本行重庆分行内部细则为 20%，低于监管下限。混用两个口径会得出错误结论。",
         tier="原文+合成"),

    # ---------------- citation 3：条款引用 ----------------
    case("citation-01-data-security-event-clause", "citation",
         "《银行保险机构数据安全管理办法》里数据安全事件报告时限规定在哪一条？请给出条款号并复述要求。",
         "第六十九条：数据安全事件发生 2 小时内向国家金融监督管理总局或其派出机构报告，24 小时内提交正式书面报告，处置期间每 2 小时上报进展。",
         [("R01", "数据安全事件发生2小时内，银行保险机构应当向国家金融监督管理总局或者其派出机构报告")],
         tier="原文"),
    case("citation-02-shanghai-qc-clause", "citation",
         "上海通知里“每月不低于 30% 质检”的要求位于第几部分第几项？",
         "位于第四部分“质检要求”第（一）项：对符合本通知要求的全部成交件视听资料按每月不低于 30% 的比例在犹豫期内全程质检。该文件按部分—项编排，没有条款号。",
         [("R07", "按每月不低于30%的比例在犹豫期内全程质检")],
         tier="原文"),
    case("citation-03-operational-risk-clause", "citation",
         "《银行保险机构操作风险管理办法》中重大操作风险事件的报告时限规定在第几条？",
         "第四十二条：在知悉或者应当知悉重大操作风险事件 5 个工作日内，按监管职责归属向国家金融监督管理总局或其派出机构报告。",
         [("R06", "银行保险机构应当在知悉或者应当知悉以下重大操作风险事件5个工作日内")],
         tier="原文"),
]


def validate(meta: dict[str, dict]) -> tuple[list[dict], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    texts = {doc_id: read_doc(doc_id) for doc_id in DOCS}
    blocks = {doc_id: split_blocks(text) for doc_id, text in texts.items()}

    seen_ids: set[str] = set()
    for item in CASES:
        if item["answer"] == "占位" or not item["question"] or item["question"] == "占位":
            errors.append(f"{item['id']}: 仍是占位内容，必须替换")
        if item["id"] in seen_ids:
            errors.append(f"{item['id']}: id 重复")
        seen_ids.add(item["id"])

        if item["category"] in ("noanswer", "clarify"):
            if item["evidence"]:
                errors.append(f"{item['id']}: {item['category']} 不应标注证据")
            continue
        if not item["evidence"]:
            errors.append(f"{item['id']}: 缺少 expectedEvidence")
            continue

        for doc_id, phrase in item["evidence"]:
            if doc_id not in DOCS:
                errors.append(f"{item['id']}: 未知文档 {doc_id}")
                continue
            haystack = normalize(texts[doc_id])
            needle = normalize(phrase)
            if needle not in haystack:
                errors.append(f"{item['id']}: 证据短语在 {doc_id} 中找不到 → {phrase}")
                continue
            hits = haystack.count(needle)
            if hits > 1:
                warnings.append(f"{item['id']}: 短语在 {doc_id} 中出现 {hits} 次（非唯一）→ {phrase}")
            if not any(needle in normalize(block["text"]) for block in blocks[doc_id]):
                errors.append(f"{item['id']}: 证据短语跨分块，会被切分切开 → {phrase}")

        for doc_id in dict.fromkeys(doc_id for doc_id, _ in item["evidence"]):
            ok, reason = retrievable(meta[doc_id])
            if not ok:
                errors.append(f"{item['id']}: 期望文件 {doc_id} 按 {AS_OF} 不可检索（{reason}）")
    return [], errors + warnings


def clause_of(doc_id: str, phrase: str, blocks: dict[str, list[dict]]) -> dict:
    needle = normalize(phrase)
    for block in blocks[doc_id]:
        if needle in normalize(block["text"]):
            return {"docId": doc_id, "file": DOCS[doc_id][1], "marker": block["marker"], "articlePath": block["path"]}
    return {"docId": doc_id, "file": DOCS[doc_id][1], "marker": "?", "articlePath": "?"}


def provenance(item: dict) -> str:
    docs = {doc_id for doc_id, _ in item["evidence"]}
    if not docs:
        return "原文语料（无期望文件）"
    has_r = any(doc_id.startswith("R") for doc_id in docs)
    has_s = any(doc_id.startswith("S") for doc_id in docs)
    if has_r and has_s:
        return "原文+合成"
    return "合成" if has_s else "原作"


def build() -> None:
    meta = load_metadata()
    _, problems = validate(meta)
    errors = [line for line in problems if "非唯一" not in line]
    warnings = [line for line in problems if "非唯一" in line]
    if errors:
        print("校验失败：")
        for line in errors:
            print("  - " + line)
        raise SystemExit(1)

    texts = {doc_id: read_doc(doc_id) for doc_id in DOCS}
    blocks = {doc_id: split_blocks(text) for doc_id, text in texts.items()}

    jsonl_lines = [
        "# bank-compliance-v1：银行/保险合规 50 题检索评测集（自动生成，请勿手改）",
        "# 生成脚本：docs/eval/tools/build_bank_compliance_eval.py",
        "# 语料：docs/compliance-demo 下 14 篇官方原文 + 5 篇 DEMO 合成资料，共 19 篇",
        "# id 前缀即题型：fact / multi / region / version / conflict / noanswer / clarify / citation",
        "# expectedFileNames 用上传时的文件名（忽略大小写匹配）；expectedEvidence 为条款原文逐字短语。",
        "# noanswer / clarify 题不标期望文件，只评回答。",
        "# expectedBehavior=clarify_first 的题目会拿到 ask_clarification 工具，评委接受“先追问”或“给数值+说明适用条件”。",
        "# 出处分档见 docs/eval/bank-compliance-v1-gold.md：原文 / 原文+合成 / 合成，做指标对比时按档拆子集。",
    ]
    ledger_cases = []
    for item in CASES:
        record = {
            "id": item["id"],
            "question": item["question"],
            "expectedFileNames": list(dict.fromkeys(DOCS[doc_id][1] for doc_id, _ in item["evidence"])),
            "expectedEvidence": [phrase for _, phrase in item["evidence"]],
            "referenceAnswer": item["answer"],
        }
        if not record["expectedEvidence"]:
            record.pop("expectedEvidence")
        if item["expectedBehavior"]:
            record["expectedBehavior"] = item["expectedBehavior"]
        jsonl_lines.append(json.dumps(record, ensure_ascii=False))

        entry = {
            "caseId": item["id"],
            "category": item["category"],
            "categoryLabel": CATEGORY_LABEL[item["category"]],
            "question": item["question"],
            "referenceAnswer": item["answer"],
            "expectedFiles": [],
            "evidence": [],
            "tier": item["tier"],
            "note": item["note"],
        }
        if item["expectedBehavior"]:
            entry["expectedBehavior"] = item["expectedBehavior"]
        for doc_id, phrase in item["evidence"]:
            clause = clause_of(doc_id, phrase, blocks)
            ok, reason = retrievable(meta[doc_id])
            entry["expectedFiles"].append({
                "docId": doc_id,
                "file": DOCS[doc_id][1],
                "title": meta[doc_id]["title"],
                "jurisdiction": meta[doc_id]["jurisdiction"],
                "docType": meta[doc_id]["docType"],
                "effectiveAt": meta[doc_id]["effectiveAt"],
                "expiresAt": meta[doc_id]["expiresAt"],
                "repealed": meta[doc_id]["repealed"],
                "validity": reason,
                "retrievable": ok,
            })
            entry["evidence"].append({"docId": doc_id, "phrase": phrase, "clause": clause["marker"], "articlePath": clause["articlePath"]})
        ledger_cases.append(entry)

    OUT_JSONL.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSONL.write_text("\n".join(jsonl_lines) + "\n", encoding="utf-8", newline="\n")

    counts: dict[str, int] = {}
    tier_counts: dict[str, int] = {}
    item_only_rows = 0
    for entry in ledger_cases:
        counts[entry["category"]] = counts.get(entry["category"], 0) + 1
        tier_counts[entry["tier"]] = tier_counts.get(entry["tier"], 0) + 1
        item_only_rows += sum(1 for ev in entry["evidence"] if ev["articlePath"] == "全文")

    gold = {
        "dataset": "bank-compliance-v1",
        "generatedAt": AS_OF.isoformat(),
        "asOf": AS_OF.isoformat(),
        "generator": "docs/eval/tools/build_bank_compliance_eval.py",
        "corpus": {
            "originals": sorted(doc_id for doc_id in DOCS if doc_id.startswith("R")),
            "synthetics": sorted(doc_id for doc_id in DOCS if doc_id.startswith("S")),
            "note": "R12 已废止、S05 已过失效日期，不作为任何题目的期望文件，只作干扰项留在库中。",
        },
        "counts": {"total": len(ledger_cases), "byCategory": counts, "byTier": tier_counts},
        "cases": ledger_cases,
    }
    OUT_GOLD_JSON.write_text(json.dumps(gold, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

    lines = [
        "# bank-compliance-v1 gold 台账",
        "",
        f"- 生成日期：{AS_OF.isoformat()}（asOf 同一日期）",
        "- 生成脚本：`docs/eval/tools/build_bank_compliance_eval.py`",
        "- 数据集：`src/main/resources/eval/bank-compliance-v1.jsonl`",
        "",
        "## 配比",
        "",
        "| 类别 | id 前缀 | 题数 |",
        "| --- | --- | --- |",
    ]
    for category, label in CATEGORY_LABEL.items():
        prefix = {"fact": "fact-", "multi": "multi-", "region": "region-", "version": "version-",
                  "conflict": "conflict-", "noanswer": "noanswer-", "clarify": "clarify-",
                  "citation": "citation-"}[category]
        lines.append(f"| {label} | `{prefix}` | {counts.get(category, 0)} |")
    lines.append(f"| 合计 | | {len(ledger_cases)} |")
    lines += ["", "## 语料出处分布", "", "| 出处 | 题数 |", "| --- | --- |"]
    for tier, number in sorted(tier_counts.items(), key=lambda kv: -kv[1]):
        lines.append(f"| {tier} | {number} |")
    lines += [
        "",
        "标注：`tier` 说明该题的参考答案主要依据哪类语料，**不等于** `expectedFileNames` 的范围（`noanswer-`/`clarify-` 不标期望文件，但仍会按参考答案出处归档）。",
        "`原文` = 只依据官方原文；`原文+合成` = 同时引用官方原文与 DEMO 合成资料；`合成` = 只依据 DEMO 合成资料。",
        "做指标对比时按 tier 拆分子集：纯原文子集不受合成资料影响。",
        "`expectedBehavior=clarify_first` 的题目会拿到 `ask_clarification` 工具，评委接受“先追问”或“给出数值并说明适用条件”。",
        "",
        "## 已知限制",
        "",
        f"- 6 篇“通知类”文件（R07/R09/R10/R11/R13/R14）用「一、二、」分项，没有「第 X 条」标记，"
        f"`ComplianceClauseChunker` 不写 `articlePath`（ES 里该字段为空），下表用「全文」占位表示无条款锚点，共 {item_only_rows} 行。",
        "  这些题的参考答案改用「第 X 部分第（X）项」定位，评测引用指标也应按此口径判读，不能要求模型给出条款号。",
        "- 评测匹配是“分块文本包含任一证据短语”，因此 `multi-`/`conflict-` 题的检索指标偏宽松；",
        "  判读这两类时应看 `docRecallAtK` / 期望文档覆盖率，而不是只看分块级 Recall@K。",
        "",
        "## 逐题索引",
        "",
        "| 题号 | 类别 | 期望文件（条款） | 证据短语 | 出处 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for entry in ledger_cases:
        files = "；".join(f"{f['file']}（{meta[f['docId']]['title']}）" for f in entry["expectedFiles"]) or "—"
        evidence = "；".join(f"{e['clause']}: {e['phrase'][:28]}…" for e in entry["evidence"]) or "—"
        tier_note = entry["tier"] + ("／澄清优先" if entry.get("expectedBehavior") else "")
        lines.append(f"| `{entry['caseId']}` | {entry['categoryLabel']} | {files} | {evidence} | {tier_note} |")
    OUT_GOLD_MD.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    print(f"写出数据集：{OUT_JSONL.relative_to(ROOT)}（{len(ledger_cases)} 题）")
    print(f"写出台账：{OUT_GOLD_JSON.relative_to(ROOT)}")
    print(f"写出台账：{OUT_GOLD_MD.relative_to(ROOT)}")
    print("配比：" + "，".join(f"{CATEGORY_LABEL[c]}={counts.get(c, 0)}" for c in CATEGORY_LABEL))
    print("出处：" + "，".join(f"{t}={n}" for t, n in sorted(tier_counts.items(), key=lambda kv: -kv[1])))
    for line in warnings:
        print("提示：" + line)


if __name__ == "__main__":
    build()
