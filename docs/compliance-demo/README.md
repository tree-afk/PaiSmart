# 银行/保险合规知识库初始演示资料

> 这些文件是**演示用合成资料**，不是真实监管文件、真实银行/保险制度或法律意见。  
> 仅用于验证 PaiSmart 的上传、条款切分、地区过滤、版本生效和引用展示功能，不能用于生产合规判断。

## 目录

```text
docs/compliance-demo/
├── corpus/                          # 默认上传的 5 篇主资料 + 1 篇未来生效新版
│   ├── 01-national-regulation-demo.md
│   ├── 02-shanghai-regulation-demo.md
│   ├── 03-internal-policy-demo.md
│   ├── 04-product-terms-demo.md
│   ├── 05-operating-guideline-demo.md
│   └── 06-national-regulation-v2-demo.md
├── edge-cases/                      # 默认不上传，用于手动验收有效期/效力状态
│   ├── expired-demo.md
│   └── unconfirmed-demo.md
├── manifest.json                    # 文件与合规元数据、版本关系
└── upload_corpus.py                 # 通过后端 API 批量上传主资料和新版本
```

## 主资料与元数据

| 文件 | 类型 | 地区 | 发布机构 | 适用机构 | 生效日期 | 版本角色 |
| --- | --- | --- | --- | --- | --- | --- |
| 01-national-regulation-demo.md | 监管规定 REGULATION | 全国 | 演示监管机构 | 商业银行、保险公司 | 2026-01-01 | 当前版本 |
| 02-shanghai-regulation-demo.md | 监管规定 REGULATION | 上海 | 演示监管机构上海监管局 | 上海地区商业银行 | 2026-03-01 | 当前版本 |
| 03-internal-policy-demo.md | 内部制度 INTERNAL_POLICY | 全国 | 演示银行总行 | 演示银行各分支机构 | 2026-02-01 | 当前版本 |
| 04-product-terms-demo.md | 产品条款 PRODUCT_TERMS | 全国 | 演示保险公司 | 购买演示重疾险产品的个人客户 | 2026-04-01 | 当前版本 |
| 05-operating-guideline-demo.md | 操作指引 GUIDELINE | 上海 | 演示银行上海分行 | 上海分行运营与合规岗位 | 2026-05-01 | 当前版本 |
| 06-national-regulation-v2-demo.md | 监管规定 REGULATION | 全国 | 演示监管机构 | 商业银行、保险公司 | 2026-12-01 | 01 的未来新版 |

当前系统日期按 2026-10-22 设计：01–05 已到生效日期；06 是未来版本，构建完成后应保持 `READY`，在 2026-12-01 前不替换 01 的 active 指针。

## 上传方式

### 方式一：前端上传

启动前端后打开 `http://localhost:9527/#/knowledge-base`，对 01–05 分别点击上传，并填写：

- 文档类型：按上表选择
- 适用地区：全国 / 上海
- 发布机构：按上表填写
- 适用机构：按上表填写
- 生效日期：按上表填写
- 失效日期：留空
- 废止标记：不勾选

06 需要通过 `POST /api/v1/upload/merge` 带 `documentId` 作为 01 的新版本上传；当前前端没有新版本上传入口。

### 方式二：脚本上传（推荐用于验收）

先确保后端、MySQL、Redis、MinIO、Kafka、Elasticsearch 已启动，然后在仓库根目录执行：

```bash
python docs/compliance-demo/upload_corpus.py
```

脚本会：

1. 读取仓库根目录 `.env` 的管理员账号登录；
2. 上传 5 篇主资料；
3. 记录 01 的 `documentId`，把 06 作为 01 的新版本上传；
4. 打印每篇的 `documentId/versionId/buildId`。

脚本不会上传 `edge-cases/`，也不包含任何密钥。

## 验收建议

- 列表应看到 01–05 为「已到生效日期」，06 为「待生效」。
- 搜索“上海 商业银行 每季度报送”应同时命中 02（上海）和 01（全国适用）。
- 搜索“全国 商业银行 数据安全事件”应命中 01，不应因为只问全国而返回上海/其他地区资料。
- 06 在 2026-12-01 前不应替换 01；日期到达后由 `DocumentBuildEffectiveJob` 切换。
- `edge-cases/expired-demo.md` 上传后应显示「已失效」，不参与检索。
- `edge-cases/unconfirmed-demo.md` 上传后应显示「效力待确认」；回答必须提示生效日期未标注，不能断言现行有效。

## 说明

- 所有条款均为合成文本，其中的机构、日期、金额、期限均为演示值。
- 如需扩充成 50 题合规评测集，请使用 `docs/eval/bank-compliance-eval-template.md` 的字段和分类，不要把本目录的演示资料当作真实 gold。
- 本目录不影响应用启动；删除它不会删除已上传的知识库数据。
