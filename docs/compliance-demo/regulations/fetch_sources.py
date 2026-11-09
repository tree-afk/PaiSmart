"""抓取公开监管原文并生成语料快照与来源台账。

只抓官方站点（金融监管总局、中国人民银行）。正文以纯文本 Markdown 落盘，
元数据写入 sources.json；不抓取真实机构内部制度，也不写入任何客户数据。

用法：python docs/compliance-demo/regulations/fetch_sources.py
"""
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path

import requests
from bs4 import BeautifulSoup

BASE = Path(__file__).resolve().parent
UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36',
      'Referer': 'https://www.nfra.gov.cn/'}

SOURCES = [
    dict(id='R01', doc_id=1192308, kind='nfra', slug='data-security-2024',
         title='银行保险机构数据安全管理办法', issuer='国家金融监督管理总局',
         document_no='金规〔2024〕24号', doc_type='监管规定', jurisdiction='全国',
         applicable='商业银行、保险公司等银行保险机构', publish_date='2024-12-27',
         status='现行有效',
         effective_date='2024-12-27'),
    dict(id='R02', doc_id=1129945, kind='nfra', slug='insurance-sales-2023',
         title='保险销售行为管理办法', issuer='国家金融监督管理总局',
         document_no='国家金融监督管理总局令2023年第2号', doc_type='监管规定', jurisdiction='全国',
         applicable='保险公司、保险中介机构', publish_date='2023-09-20',
         status='现行有效',
         effective_date='2024-03-01'),
    dict(id='R03', doc_id=1087524, kind='nfra', slug='consumer-protection-2022',
         title='银行保险机构消费者权益保护管理办法', issuer='中国银行保险监督管理委员会',
         document_no='中国银行保险监督管理委员会令2022年第9号', doc_type='监管规定', jurisdiction='全国',
         applicable='银行业金融机构、保险机构', publish_date='2022-12-26',
         status='现行有效',
         effective_date='2023-03-01'),
    dict(id='R04', kind='pbc',
         url='https://www.pbc.gov.cn/tiaofasi/144941/144957/4099060/index.html',
         slug='pbc-consumer-protection-2020',
         title='中国人民银行金融消费者权益保护实施办法', issuer='中国人民银行',
         document_no='中国人民银行令〔2020〕第5号', doc_type='监管规定', jurisdiction='全国',
         applicable='银行业金融机构、非银行支付机构', publish_date='2020-09-15',
         status='现行有效',
         effective_date='2020-11-01'),
    dict(id='R05', doc_id=949137, kind='nfra', slug='internet-insurance-2020',
         title='互联网保险业务监管办法', issuer='中国银行保险监督管理委员会',
         document_no='中国银行保险监督管理委员会令2020年第13号', doc_type='监管规定', jurisdiction='全国',
         applicable='保险机构、保险中介机构', publish_date='2020-12-07',
         status='现行有效',
         effective_date='2021-02-01'),
    dict(id='R06', doc_id=1144382, kind='nfra', slug='operational-risk-2023',
         title='银行保险机构操作风险管理办法', issuer='国家金融监督管理总局',
         document_no='国家金融监督管理总局令2023年第5号', doc_type='监管规定', jurisdiction='全国',
         applicable='银行保险机构', publish_date='2023-12-27',
         status='现行有效',
         effective_date='2024-07-01'),
    dict(id='R07', doc_id=954262, kind='nfra', slug='shanghai-sales-recording-2020',
         title='上海银保监局关于深入推进人身保险销售行为可回溯管理的通知',
         issuer='原上海银保监局', document_no='沪银保监通〔2020〕48号', doc_type='监管规定',
         jurisdiction='上海', applicable='在沪人身保险公司、保险专业中介机构', publish_date='2020-12-03',
         status='现行有效',
         effective_date='2021-04-01'),
    dict(id='R08', doc_id=1082153, kind='nfra', slug='shanghai-medical-insurance-2022',
         title='上海市医保个人账户购买商业健康保险业务管理办法',
         issuer='原上海银保监局', document_no='沪银保监规〔2022〕3号', doc_type='监管规定',
         jurisdiction='上海', applicable='在沪人身保险公司、财产保险公司', publish_date='2022-11-18',
         status='现行有效',
         effective_date='2022-11-18'),
    dict(id='R09', doc_id=967445, kind='nfra', slug='internet-loan-2021',
         title='中国银保监会办公厅关于进一步规范商业银行互联网贷款业务的通知',
         issuer='中国银保监会办公厅', document_no='银保监办发〔2021〕24号', doc_type='监管规定',
         jurisdiction='全国', applicable='商业银行', publish_date='2021-02-20',
         status='现行有效',
         effective_date='2022-01-01'),
    dict(id='R10', doc_id=1061876, kind='nfra', slug='internet-loan-2022',
         title='中国银保监会关于加强商业银行互联网贷款业务管理提升金融服务质效的通知',
         issuer='中国银保监会', document_no='银保监规〔2022〕14号', doc_type='监管规定',
         jurisdiction='全国', applicable='商业银行', publish_date='2022-07-15',
         status='现行有效',
         effective_date='2022-07-15'),
    dict(id='R11', doc_id=912732, kind='nfra', slug='internet-insurance-recording-2020',
         title='中国银保监会关于规范互联网保险销售行为可回溯管理的通知',
         issuer='中国银保监会', document_no='银保监发〔2020〕26号', doc_type='监管规定',
         jurisdiction='全国', applicable='保险机构、保险中介机构', publish_date='2020-06-30',
         status='现行有效',
         effective_date='2020-10-01'),
    dict(id='R12', kind='gov',
         url='http://www.gov.cn/gongbao/content/2015/content_2978283.htm',
         slug='internet-insurance-interim-2015',
         title='互联网保险业务监管暂行办法', issuer='原中国保险监督管理委员会',
         document_no='保监发〔2015〕69号', doc_type='监管规定', jurisdiction='全国',
         applicable='保险机构、保险中介机构、第三方网络平台', publish_date='2015-07-22',
         status='已废止', effective_date='2015-10-01',
         superseded_by='R05', abolished_date='2021-02-01'),
    dict(id='R13', doc_id=1251783, kind='nfra', slug='xinjiang-sales-recording-2026',
         title='新疆金融监管局关于进一步加强人身保险销售行为可回溯管理的通知',
         issuer='国家金融监督管理总局新疆监管局', document_no='新金监规〔2026〕1号',
         doc_type='监管规定', jurisdiction='新疆',
         applicable='新疆辖内人身保险公司、保险专业中介机构、保险兼业代理机构',
         publish_date='2026-03-18', status='现行有效', effective_date='2026-02-06'),
    dict(id='R14', doc_id=1275226, kind='nfra', slug='chongqing-internet-loan-2026',
         title='重庆金融监管局关于进一步加强辖内银行业金融机构互联网贷款业务风险管理的通知',
         issuer='国家金融监督管理总局重庆监管局', document_no='渝金管规〔2026〕2号',
         doc_type='监管规定', jurisdiction='重庆',
         applicable='重庆辖内大型银行、股份制银行、地方法人银行、消费金融公司、村镇银行',
         publish_date='2026-10-20', status='现行有效', effective_date='2026-09-18'),
]


# 结构单元与生效口径：逐篇按正文核对，不按站点元数据推断
STRUCTURE = {
    'R01': ('条', 'full', '自公布之日（2024-12-27）起施行；同时废止《银行保险机构数据安全办法》（银保监办发〔2022〕118号）'),
    'R02': ('条', 'full', '自2024-03-01起施行'),
    'R03': ('条', 'full', '自2023-03-01起施行'),
    'R04': ('条', 'full', '自2020-11-01起施行'),
    'R05': ('条', 'full', '自2021-02-01起施行'),
    'R06': ('条+项', 'full', '自2024-07-01起施行；正文61条，附件另有12项'),
    'R07': ('项', 'phased', '分阶段实施：2021-01-01至2021-03-31为过渡期试点，2021-04-01起正式实施'),
    'R08': ('条', 'full', '自发布之日（2022-11-18）起施行'),
    'R09': ('项', 'partial', '落款2021-02-19，2021-02-20发布；仅第二条、第五条自2022-01-01起执行，其余条款过渡期与《商业银行互联网贷款管理暂行办法》一致'),
    'R10': ('项', 'partial', '落款2022-07-12，2022-07-15发布；未单列施行日期，按发布之日起执行，存量业务过渡期至2023-06-30'),
    'R11': ('项', 'full', '自2020-10-01起实施'),
    'R12': ('条', 'full', '自2015-10-01起施行，施行期限3年；经延期后由R05《互联网保险业务监管办法》自2021-02-01起废止'),
    'R13': ('项', 'full', '未单列施行日期，按印发之日（落款2026-02-06）起执行；站点发布日2026-03-18'),
    'R14': ('项', 'full', '未单列施行日期，按印发之日（落款2026-09-18）起执行；站点发布日2026-10-20'),
}

ARTICLE_RE = re.compile(r'^第[一二三四五六七八九十百零〇]+条', re.M)
ITEM_RE = re.compile(r'^[一二三四五六七八九十]+、', re.M)


def clean_lines(text):
    text = text.replace('\xa0', ' ')
    lines = []
    for line in text.splitlines():
        line = re.sub(r'[ \t]+', ' ', line).strip()
        if line:
            lines.append(line)
    return '\n'.join(lines)


def paragraphs_from_html(html):
    soup = BeautifulSoup(html, 'html.parser')
    for tag in soup(['script', 'style']):
        tag.decompose()
    for br in soup.find_all('br'):
        br.replace_with('\n')
    paras = []
    for p in soup.find_all('p'):
        text = p.get_text('').replace('\xa0', ' ')
        text = re.sub(r'[ \t]+', ' ', text).strip()
        if text:
            paras.append(text)
    return '\n'.join(paras)


def fetch_nfra(src):
    url = 'https://www.nfra.gov.cn/cbircweb/DocInfo/SelectByDocId?docId=%d' % src['doc_id']
    resp = requests.get(url, headers=UA, timeout=60)
    resp.raise_for_status()
    data = resp.json()['data']
    text = clean_lines(paragraphs_from_html(data['docClob']))
    source_url = 'https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=%d' % src['doc_id']
    return text, source_url, data.get('caption') or ''


def fetch_pbc(src):
    resp = requests.get(src['url'], headers=UA, timeout=60)
    resp.raise_for_status()
    resp.encoding = resp.apparent_encoding or 'utf-8'
    soup = BeautifulSoup(resp.text, 'html.parser')
    container = soup.find(class_=re.compile(r'\bcontent\b'))
    container = container or soup
    for tag in container(['script', 'style']):
        tag.decompose()
    for br in container.find_all('br'):
        br.replace_with('\n')
    paras = []
    for p in container.find_all('p'):
        text = p.get_text('').replace('\xa0', ' ')
        text = re.sub(r'[ \t]+', ' ', text).strip()
        if text:
            paras.append(text)
    if not paras:
        paras = [clean_lines(container.get_text('\n'))]
    return '\n'.join(paras), src['url'], ''


def fetch_gov(src):
    resp = requests.get(src['url'], headers=UA, timeout=60)
    resp.raise_for_status()
    resp.encoding = resp.apparent_encoding or 'utf-8'
    soup = BeautifulSoup(resp.text, 'html.parser')
    box = soup.find(id='UCAP-CONTENT') or soup
    for tag in box(['script', 'style']):
        tag.decompose()
    for br in box.find_all('br'):
        br.replace_with('\n')
    paras = []
    for p in box.find_all('p'):
        for line in p.get_text('').replace('\xa0', ' ').splitlines():
            line = re.sub(r'[ \t\u3000]+', ' ', line).strip()
            if line:
                paras.append(line)
    return '\n'.join(paras), src['url'], ''


def build_entry(src, target, text, url, caption):
    article_count = len(ARTICLE_RE.findall(text))
    item_count = len(ITEM_RE.findall(text))
    unit, scope, curated_caption = STRUCTURE[src['id']]
    if article_count and item_count:
        structure = '%d条 + %d项' % (article_count, item_count)
    elif article_count:
        structure = '%d条' % article_count
    else:
        structure = '%d项' % item_count
    entry = dict(
        id=src['id'], title=src['title'], file=target.name, issuer=src['issuer'],
        documentNo=src['document_no'], docType=src['doc_type'], jurisdiction=src['jurisdiction'],
        applicableInstitutions=src['applicable'], publishDate=src['publish_date'],
        status=src['status'], effectiveDate=src['effective_date'],
        effectiveScope=scope, effectiveCaption=curated_caption or caption,
        clauseUnit=unit, articleCount=article_count, itemCount=item_count, structure=structure,
        sourceUrl=url, fetchedAt=date.today().isoformat(),
        charCount=len(text), sha256=hashlib.sha256(text.encode('utf-8')).hexdigest(),
    )
    if src.get('superseded_by'):
        entry['supersededBy'] = src['superseded_by']
        entry['abolishedDate'] = src['abolished_date']
    return entry


FETCHERS = {'nfra': fetch_nfra, 'pbc': fetch_pbc, 'gov': fetch_gov}


def main():
    fetched = []
    failures = []
    for src in SOURCES:
        last_error = None
        for _ in range(2):
            try:
                text, url, caption = FETCHERS[src['kind']](src)
                break
            except Exception as exc:
                last_error = exc
                text = None
        if text is None:
            failures.append((src['id'], last_error))
            continue
        if len(text) < 500:
            failures.append((src['id'], '正文过短（%d 字符）' % len(text)))
            continue
        fetched.append((src, text, url, caption))
    if failures:
        for doc_id, err in failures:
            print('抓取失败 %s：%s' % (doc_id, err), file=sys.stderr)
        raise SystemExit('有 %d 篇失败，未写入任何文件' % len(failures))

    ledger = []
    for src, text, url, caption in fetched:
        article_count = len(ARTICLE_RE.findall(text))
        item_count = len(ITEM_RE.findall(text))
        target = BASE / (src['id'] + '-' + src['slug'] + '.md')
        body = '# %s\n\n%s\n' % (src['title'], text)
        target.write_text(body, encoding='utf-8', newline='\n')
        ledger.append(build_entry(src, target, text, url, caption))
        print('%s %-42s chars=%5d 条=%3d 项=%3d' % (src['id'], src['title'], len(text), article_count, item_count))
    (BASE / 'sources.json').write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + '\n',
                                       encoding='utf-8')
    print('写入 %d 篇，台账：%s' % (len(ledger), BASE / 'sources.json'))


if __name__ == '__main__':
    main()
