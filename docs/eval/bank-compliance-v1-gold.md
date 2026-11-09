# bank-compliance-v1 gold 台账

- 生成日期：2026-11-06（asOf 同一日期）
- 生成脚本：`docs/eval/tools/build_bank_compliance_eval.py`
- 数据集：`src/main/resources/eval/bank-compliance-v1.jsonl`

## 配比

| 类别 | id 前缀 | 题数 |
| --- | --- | --- |
| 条款事实 | `fact-` | 12 |
| 跨文档综合 | `multi-` | 8 |
| 地区适用 | `region-` | 6 |
| 版本/当前可检索 | `version-` | 6 |
| 冲突条款 | `conflict-` | 5 |
| 无答案/应拒答 | `noanswer-` | 6 |
| 缺用户条件/应澄清 | `clarify-` | 4 |
| 条款引用 | `citation-` | 3 |
| 合计 | | 50 |

## 语料出处分布

| 出处 | 题数 |
| --- | --- |
| 原文 | 41 |
| 原文+合成 | 6 |
| 合成 | 3 |

标注：`tier` 说明该题的参考答案主要依据哪类语料，**不等于** `expectedFileNames` 的范围（`noanswer-`/`clarify-` 不标期望文件，但仍会按参考答案出处归档）。
`原文` = 只依据官方原文；`原文+合成` = 同时引用官方原文与 DEMO 合成资料；`合成` = 只依据 DEMO 合成资料。
做指标对比时按 tier 拆分子集：纯原文子集不受合成资料影响。
`expectedBehavior=clarify_first` 的题目会拿到 `ask_clarification` 工具，评委接受“先追问”或“给出数值并说明适用条件”。

## 已知限制

- 6 篇“通知类”文件（R07/R09/R10/R11/R13/R14）用「一、二、」分项，没有「第 X 条」标记，`ComplianceClauseChunker` 不写 `articlePath`（ES 里该字段为空），下表用「全文」占位表示无条款锚点，共 18 行。
  这些题的参考答案改用「第 X 部分第（X）项」定位，评测引用指标也应按此口径判读，不能要求模型给出条款号。
- 评测匹配是“分块文本包含任一证据短语”，因此 `multi-`/`conflict-` 题的检索指标偏宽松；
  判读这两类时应看 `docRecallAtK` / 期望文档覆盖率，而不是只看分块级 Recall@K。

## 逐题索引

| 题号 | 类别 | 期望文件（条款） | 证据短语 | 出处 |
| --- | --- | --- | --- | --- |
| `fact-01-data-security-event-report` | 条款事实 | R01-data-security-2024.md（银行保险机构数据安全管理办法） | 第六十九条: 数据安全事件发生2小时内，银行保险机构应当向国家金融监督… | 原文 |
| `fact-02-data-security-annual-report` | 条款事实 | R01-data-security-2024.md（银行保险机构数据安全管理办法） | 第七十四条: 每年1月15日前向国家金融监督管理总局或者其派出机构报送… | 原文 |
| `fact-03-data-security-penalty` | 条款事实 | R01-data-security-2024.md（银行保险机构数据安全管理办法） | 第七十七条: 责令银行业金融机构改正，并处以二十万以上五十万以下罚款… | 原文 |
| `fact-04-sales-staff-leave-notice` | 条款事实 | R02-insurance-sales-2023.md（保险销售行为管理办法） | 第三十六条: 在该保险销售人员的离职手续办理完成后的30日内明确通知投… | 原文 |
| `fact-05-consumer-protection-audit-cycle` | 条款事实 | R03-consumer-protection-2022.md（银行保险机构消费者权益保护管理办法） | 第十九条: 以5年为一个周期全面覆盖本机构相关部门和一级分支机构… | 原文 |
| `fact-06-consumer-protection-fine` | 条款事实 | R03-consumer-protection-2022.md（银行保险机构消费者权益保护管理办法） | 第五十四条: 并处以10万元以下罚款… | 原文 |
| `fact-07-pbc-complaint-handling-days` | 条款事实 | R04-pbc-consumer-protection-2020.md（中国人民银行金融消费者权益保护实施办法） | 第四十三条: 应当自收到投诉之日起7个工作日内作出下列处理… | 原文 |
| `fact-08-pbc-self-assessment-report` | 条款事实 | R04-pbc-consumer-protection-2020.md（中国人民银行金融消费者权益保护实施办法） | 第五十六条: 并于次年1月31日前向中国人民银行或其分支机构报送自评估… | 原文 |
| `fact-09-internet-insurance-suspend-notice` | 条款事实 | R05-internet-insurance-2020.md（互联网保险业务监管办法） | 第八条: 应至少提前20个工作日在官方网站和自营网络平台发布公告… | 原文 |
| `fact-10-internet-insurance-annual-report` | 条款事实 | R05-internet-insurance-2020.md（互联网保险业务监管办法） | 第七十四条: 保险机构应于每年4月30日前向互联网保险监管相关信息系统… | 原文 |
| `fact-11-operational-risk-report-days` | 条款事实 | R06-operational-risk-2023.md（银行保险机构操作风险管理办法） | 第四十二条: 银行保险机构应当在知悉或者应当知悉以下重大操作风险事件5… | 原文 |
| `fact-12-complaint-records-retention` | 条款事实 | R04-pbc-consumer-protection-2020.md（中国人民银行金融消费者权益保护实施办法） | 第四十四条: 投诉资料留存时间自投诉办结之日起不得少于3年… | 原文 |
| `multi-01-incident-report-deadlines` | 跨文档综合 | R01-data-security-2024.md（银行保险机构数据安全管理办法）；R06-operational-risk-2023.md（银行保险机构操作风险管理办法） | 第六十九条: 数据安全事件发生2小时内，银行保险机构应当向国家金融监督…；第四十二条: 银行保险机构应当在知悉或者应当知悉以下重大操作风险事件5… | 原文 |
| `multi-02-recording-retention` | 跨文档综合 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知）；R11-internet-insurance-recording-2020.md（中国银保监会关于规范互联网保险销售行为可回溯管理的通知） | 全文: 保险期间在一年及以下的不得少于五年，保险期间超过一年的不…；全文: 遇消费者投诉、法律诉讼等纠纷的，应当至少保存至纠纷结束后… | 原文 |
| `multi-03-internet-loan-cooperation` | 跨文档综合 | R09-internet-loan-2021.md（中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知）；R14-chongqing-internet-loan-2026.md（重庆金融监管局关于进一步加强辖内银行业金融机构互联网贷款业务风险管理的通知） | 全文: 单笔贷款中合作方出资比例不得低于30%…；全文: 自通知印发之日起三个月内向我局报送专项排查报告… | 原文 |
| `multi-04-staff-notice-and-annual-report` | 跨文档综合 | R02-insurance-sales-2023.md（保险销售行为管理办法）；R05-internet-insurance-2020.md（互联网保险业务监管办法） | 第三十六条: 在该保险销售人员的离职手续办理完成后的30日内明确通知投…；第七十四条: 每年4月30日前向互联网保险监管相关信息系统报送上一年度… | 原文 |
| `multi-05-audit-cycle-and-complaint` | 跨文档综合 | R03-consumer-protection-2022.md（银行保险机构消费者权益保护管理办法）；R04-pbc-consumer-protection-2020.md（中国人民银行金融消费者权益保护实施办法） | 第十九条: 以5年为一个周期全面覆盖本机构相关部门和一级分支机构…；第四十三条: 应当自收到投诉之日起7个工作日内作出下列处理… | 原文 |
| `multi-06-rectification-deadlines` | 跨文档综合 | R06-operational-risk-2023.md（银行保险机构操作风险管理办法）；R05-internet-insurance-2020.md（互联网保险业务监管办法） | 第五十条: 规模较大的保险机构自本办法施行之日起1年内执行…；第八十一条: 3个月内完成制度建设、营销宣传、销售管理、信息披露等问题… | 原文 |
| `multi-07-quality-check-ratio-two-regions` | 跨文档综合 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知）；R13-xinjiang-sales-recording-2026.md（新疆金融监管局关于进一步加强人身保险销售行为可回溯管理的通知） | 全文: 按每月不低于30%的比例在犹豫期内全程质检…；全文: 针对投保人为特殊人群的录音录像保单，每月质检覆盖率不得低… | 原文 |
| `multi-08-medical-account-and-complaint` | 跨文档综合 | R08-shanghai-medical-insurance-2022.md（上海市医保个人账户购买商业健康保险业务管理办法）；R04-pbc-consumer-protection-2020.md（中国人民银行金融消费者权益保护实施办法） | 第十一条: 退保金必须退回投保人本人的医保个人账户…；第四十四条: 投诉资料留存时间自投诉办结之日起不得少于3年… | 原文 |
| `region-01-shanghai-recording-scope` | 地区适用 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知） | 全文: 除电话销售业务和互联网保险业务外，在上海地区销售人身保险… | 原文 |
| `region-02-shanghai-identity-verification` | 地区适用 | R08-shanghai-medical-insurance-2022.md（上海市医保个人账户购买商业健康保险业务管理办法） | 第九条: 确保投保行为系医保个人账户持有人本人的真实意愿… | 原文 |
| `region-03-shanghai-policy-loan` | 地区适用 | R08-shanghai-medical-insurance-2022.md（上海市医保个人账户购买商业健康保险业务管理办法） | 第十三条: 保险公司不得提供医保个人账户购买商业健康保险产品的保单贷… | 原文 |
| `region-04-xinjiang-special-group-qc` | 地区适用 | R13-xinjiang-sales-recording-2026.md（新疆金融监管局关于进一步加强人身保险销售行为可回溯管理的通知） | 全文: 针对投保人为特殊人群的录音录像保单，每月质检覆盖率不得低… | 原文 |
| `region-05-chongqing-special-report` | 地区适用 | R14-chongqing-internet-loan-2026.md（重庆金融监管局关于进一步加强辖内银行业金融机构互联网贷款业务风险管理的通知） | 全文: 自通知印发之日起三个月内向我局报送专项排查报告… | 原文 |
| `region-06-xinjiang-agent-feedback` | 地区适用 | R13-xinjiang-sales-recording-2026.md（新疆金融监管局关于进一步加强人身保险销售行为可回溯管理的通知） | 全文: 并在质检完成后的10个工作日内，将全部质检结果书面反馈至… | 原文 |
| `version-01-internet-insurance-effective` | 版本/当前可检索 | R05-internet-insurance-2020.md（互联网保险业务监管办法） | 第八十二条: 本办法自2021年2月1日起施行，《互联网保险业务监管暂… | 原文 |
| `version-02-internet-loan-effective` | 版本/当前可检索 | R09-internet-loan-2021.md（中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知） | 全文: 本通知第二条、第五条自2022年1月1日起执行… | 原文 |
| `version-03-internet-loan-transition` | 版本/当前可检索 | R10-internet-loan-2022.md（中国银保监会关于加强商业银行互联网贷款业务管理提升金融服务质效的通知） | 全文: 商业银行互联网贷款存量业务过渡期至2023年6月30日… | 原文 |
| `version-04-operational-risk-execution` | 版本/当前可检索 | R06-operational-risk-2023.md（银行保险机构操作风险管理办法） | 第五十条: 规模较大的保险机构自本办法施行之日起1年内执行… | 原文 |
| `version-05-shanghai-recording-phases` | 版本/当前可检索 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知） | 全文: 第二阶段：2021年4月1日开始，正式实施… | 原文 |
| `version-06-critical-illness-version` | 版本/当前可检索 | S04-product-critical-illness-2026-demo.md（演示安康重大疾病保险产品条款（2026版）） | 第四条: 自本合同生效之日起90日为等待期… | 合成 |
| `conflict-01-data-security-event-deadline` | 冲突条款 | R01-data-security-2024.md（银行保险机构数据安全管理办法）；S01-internal-data-security-demo.md（演示银行数据安全管理办法（内部制度）） | 第六十九条: 数据安全事件发生2小时内，银行保险机构应当向国家金融监督…；第七条: 本行应当在4小时内向国家金融监督管理总局及其派出机构报告… | 原文+合成 |
| `conflict-02-quality-check-ratio` | 冲突条款 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知）；S02-internal-shanghai-sales-recording-demo.md（演示银行上海分行人身保险销售可回溯管理细则（内部制度）） | 全文: 按每月不低于30%的比例在犹豫期内全程质检…；第五条: 按每月不低于10%的比例在犹豫期内全程质检… | 原文+合成 |
| `conflict-03-rectification-deadline` | 冲突条款 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知）；S02-internal-shanghai-sales-recording-demo.md（演示银行上海分行人身保险销售可回溯管理细则（内部制度）） | 全文: 应当自发现问题之日起15个工作日内整改…；第六条: 自发现问题之日起20个工作日内完成整改… | 原文+合成 |
| `conflict-04-cooperation-funding-ratio` | 冲突条款 | R09-internet-loan-2021.md（中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知）；S03-internal-chongqing-internet-loan-demo.md（演示银行重庆分行互联网贷款合作机构管理细则（内部制度）） | 全文: 单笔贷款中合作方出资比例不得低于30%…；第四条: 单笔贷款中合作方出资比例不得低于20%… | 原文+合成 |
| `conflict-05-concentration-limits` | 冲突条款 | R09-internet-loan-2021.md（中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知）；S03-internal-chongqing-internet-loan-demo.md（演示银行重庆分行互联网贷款合作机构管理细则（内部制度）） | 全文: 与单一合作方（含其关联方）发放的本行贷款余额不得超过本行…；第五条: 不得超过本行一级资本净额的35%… | 原文+合成 |
| `noanswer-01-it-outsourcing-rule` | 无答案/应拒答 | — | — | 原文 |
| `noanswer-02-guarantee-fee-cap` | 无答案/应拒答 | — | — | 原文 |
| `noanswer-03-xinjiang-internet-loan` | 无答案/应拒答 | — | — | 原文 |
| `noanswer-04-tax-deferred-pension` | 无答案/应拒答 | — | — | 原文 |
| `noanswer-05-2027-budget` | 无答案/应拒答 | — | — | 原文 |
| `noanswer-06-critical-illness-disease-list` | 无答案/应拒答 | — | — | 合成 |
| `clarify-01-quality-check-ratio` | 缺用户条件/应澄清 | — | — | 原文 |
| `clarify-02-waiting-period` | 缺用户条件/应澄清 | — | — | 合成／澄清优先 |
| `clarify-03-complaint-deadline` | 缺用户条件/应澄清 | — | — | 原文 |
| `clarify-04-funding-ratio` | 缺用户条件/应澄清 | — | — | 原文+合成 |
| `citation-01-data-security-event-clause` | 条款引用 | R01-data-security-2024.md（银行保险机构数据安全管理办法） | 第六十九条: 数据安全事件发生2小时内，银行保险机构应当向国家金融监督… | 原文 |
| `citation-02-shanghai-qc-clause` | 条款引用 | R07-shanghai-sales-recording-2020.md（上海银保监局关于深入推进人身保险销售行为可回溯管理的通知） | 全文: 按每月不低于30%的比例在犹豫期内全程质检… | 原文 |
| `citation-03-operational-risk-clause` | 条款引用 | R06-operational-risk-2023.md（银行保险机构操作风险管理办法） | 第四十二条: 银行保险机构应当在知悉或者应当知悉以下重大操作风险事件5… | 原文 |
