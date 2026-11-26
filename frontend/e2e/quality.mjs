const policy = {
  reimbursementCurrent: 'FIN-TR-017',
  reimbursementHistoric: 'FIN-TR-017',
  lodging: 'FIN-TR-022',
  uncertain: 'FIN-TR-031',
  restricted: 'FIN-SP-006',
  guide: 'FIN-FMT-101',
  approvals: 'FIN-FMT-102',
  budget: 'FIN-FMT-103',
  training: 'FIN-FMT-104',
  settlement: 'FIN-FMT-105',
}

const evidence = (code, anchor, location, name) => ({
  code,
  anchor,
  ...(location ? { location } : {}),
  ...(name ? { name } : {}),
})

// 固定题卡来自 docs/e2e-fixtures，金额和条款均为合成。每题独立提问。
const restrictedQuestion = '高原设备验收专项的驻点津贴是多少？'
const noEvidenceText =
  /(?:没有|暂无).{0,18}(?:依据|证据|资料|信息)|无(?:足够|相关|有效)?(?:依据|证据|资料)|(?:未|没有|没能)(?:能)?(?:找到|检索到|查到|发现|提供|提及|明确)|(?:依据|证据|资料|信息).{0,16}(?:不足|没有|不含|不包含|未载明)|(?:无法|不能)(?:从.{1,24})?(?:确定|确认|提供|回答)/
const lodgingRow = /B[ \t]*档[^\r\n]{0,45}成都[^\r\n]{0,45}(?<!\d)460(?!\d)/
const tableAmount = (amount) =>
  new RegExp(
    `(?<![\\d.])${amount}(?:\\.00)?(?=\\t|[ \\t]*(?:元|人民币|[|｜,，;；:：（(]|$))`,
    'm',
  )

const qualityCases = [
  {
    id: 'QA01',
    title: '现行报销时限',
    question: '境内出差回来以后，最晚要在多久内提交报销？返程当天算不算？',
    scope: 'normal',
    role: 'reader',
    kind: 'ANSWER',
    expected:
      '返程后 15 个工作日内提交，返程当天不计；节假日按公司工作日历执行。',
    requiredFacts: [
      /15\s*个\s*工作日/,
      /返程当天.{0,8}不计|不计.{0,8}返程当天/,
    ],
    requiredEvidence: [
      evidence(
        policy.reimbursementCurrent,
        /15\s*个\s*工作日内/,
        /§?\s*3[.、]?2|报销时限/,
        /(?:v|版本\s*)?2[.]1/i,
      ),
    ],
    forbiddenFacts: [/10\s*个\s*工作日/],
  },
  {
    id: 'QA02',
    title: '口语改写报销期限',
    question: '我出完差回来了，几天之内得把单子交上去？',
    scope: 'normal',
    role: 'reader',
    kind: 'ANSWER',
    expected: '按现行办法，返程后 15 个工作日内提交，返程当天不计。',
    requiredFacts: [
      /15\s*个\s*工作日/,
      /返程当天.{0,8}不计|不计.{0,8}返程当天/,
    ],
    requiredEvidence: [
      evidence(
        policy.reimbursementCurrent,
        /15\s*个\s*工作日内/,
        /§?\s*3[.、]?2|报销时限/,
        /(?:v|版本\s*)?2[.]1/i,
      ),
    ],
    forbiddenFacts: [/10\s*个\s*工作日/],
  },
  {
    id: 'QA03',
    title: '费用分摊信息',
    question: '几个人一起出差时，报销单上要把费用怎么写清楚？',
    scope: 'normal',
    role: 'reader',
    kind: 'ANSWER',
    expected: '多人同行应注明受益人和费用分摊方式。',
    requiredFacts: [/受益人/, /费用分摊方式/],
    requiredEvidence: [
      evidence(
        policy.reimbursementCurrent,
        /注明受益人和费用分摊方式/,
        /§?\s*3[.、]?3|随单材料/,
        /(?:v|版本\s*)?2[.]1/i,
      ),
    ],
  },
  {
    id: 'QA04',
    title: '住宿表格城市行与单位',
    question: '成都出差住一晚，正式员工的住宿限额是多少？',
    scope: 'normal',
    role: 'reader',
    kind: 'ANSWER',
    expected: '成都属 B 档，限额 460 元/人/晚，金额含税。',
    requiredFacts: [
      /成都/,
      /B\s*档/,
      tableAmount(460),
      /元|人民币/,
      /(?:每人每晚|每人\s*\/\s*晚|人\s*\/\s*晚)/,
      /含税/,
    ],
    requiredEvidence: [
      evidence(policy.lodging, lodgingRow, /§?\s*2[.、]?1|城市分档/),
    ],
    forbiddenFacts: [
      /成都[^\r\n。；;]{0,20}(?:限额|上限|最高)[^\r\n。；;]{0,12}(?<!\d)(?:680|350)\s*(?:元|人民币)/,
      /B[ \t]*档[ \t]*[|｜][^\r\n|｜]*成都[^\r\n|｜]*[|｜][ \t]*(?:680|350)[ \t]*[|｜]/,
    ],
  },
  {
    id: 'QA05',
    title: '现行与归档版本对比',
    question:
      '对比当前办法和 2024 年底以前的归档版，报销期限分别是多少？10 个工作日现在还适用吗？',
    scope: 'comparison',
    role: 'reader',
    kind: 'ANSWER',
    expected:
      '现行 v2.1 为 15 个工作日；归档 v1.4 在其 2023-01-01 至 2024-12-31 适用期内为 10 个工作日，不能把旧期限当作现行要求。',
    requiredFacts: [
      /现行|当前/,
      /15\s*个\s*工作日/,
      /10\s*个\s*工作日/,
      /2023\s*年?\s*1\s*月\s*1\s*日|2023-01-01/,
      /2024\s*年?\s*12\s*月\s*31\s*日|2024-12-31/,
    ],
    requiredEvidence: [
      evidence(
        policy.reimbursementCurrent,
        /15\s*个\s*工作日内/,
        /§?\s*3[.、]?2|报销时限/,
        /(?:v|版本\s*)?2[.]1/i,
      ),
      evidence(
        policy.reimbursementHistoric,
        /10\s*个\s*工作日内/,
        /§?\s*3[.、]?2|报销时限/,
        /(?:v|版本\s*)?1[.]4/i,
      ),
    ],
    forbiddenFacts: [
      /(?:现行|当前)(?:版|规定|办法)?[ \t]*(?:为|是|规定为|需在)[ \t]*(?:返程后[ \t]*)?(?:10|十)\s*个\s*工作日/,
    ],
  },
  {
    id: 'QA06',
    title: '历史空间旧版规则',
    question:
      '请按 2024 年的归档版核对：当时返程后最迟多少个工作日交报销申请？',
    scope: 'historical',
    role: 'reader',
    kind: 'EXPIRED',
    expected:
      '历史归档 v1.4 规定返程后 10 个工作日内提交，返程当天不计；该版本适用期至 2024-12-31。',
    requiredFacts: [
      /10\s*个\s*工作日/,
      /返程当天.{0,8}不计|不计.{0,8}返程当天/,
      /2024\s*年?\s*12\s*月\s*31\s*日|2024-12-31/,
    ],
    requiredEvidence: [
      evidence(
        policy.reimbursementHistoric,
        /10\s*个\s*工作日内/,
        /§?\s*3[.、]?2|报销时限/,
        /(?:v|版本\s*)?1[.]4/i,
      ),
    ],
    forbiddenFacts: [/15\s*个\s*工作日/],
  },
  {
    id: 'QA07',
    title: '待核实口径不得当作现行政策',
    question: '夜间跨城驻点每晚有 90 元补贴吗？现在能按正式规定报销吗？',
    scope: 'uncertain',
    role: 'reader',
    kind: 'UNCERTAIN',
    expected:
      '材料是讨论稿，建议 90 元/晚，但起算条件、费用归集和生效日期待财务运营部确认，不能据此认定为现行规定。',
    requiredFacts: [
      /90\s*元\s*\/\s*晚|90\s*元每晚/,
      /讨论稿|业务讨论稿|建议/,
      /(?:起算条件|生效日期|费用归集).{0,20}待.{0,8}确认|待.{0,8}确认.{0,20}(?:起算条件|生效日期|费用归集)/,
    ],
    requiredEvidence: [
      evidence(
        policy.uncertain,
        /90\s*元\s*\/\s*晚[\s\S]{0,100}(?:生效日期|起算条件).{0,12}待.{0,8}确认/,
        /§?\s*2[.、]?1|夜间驻点补贴/,
        /(?:v|版本\s*)?0[.]9/i,
      ),
    ],
    forbiddenFacts: [/(?:已经|现行|正式规定).{0,16}90\s*元/],
  },
  {
    id: 'QA08',
    title: '跨来源组合问答',
    question: '成都出差回来后，报销期限和每晚住宿限额各是多少？',
    scope: 'normal',
    role: 'reader',
    kind: 'ANSWER',
    expected:
      '报销在返程后 15 个工作日内提交（返程当天不计）；成都住宿限额为含税 460 元/人/晚。',
    requiredFacts: [
      /15\s*个\s*工作日/,
      /返程当天.{0,8}不计|不计.{0,8}返程当天/,
      /成都/,
      tableAmount(460),
      /元|人民币/,
      /(?:每人每晚|每人\s*\/\s*晚|人\s*\/\s*晚)/,
    ],
    requiredEvidence: [
      evidence(
        policy.reimbursementCurrent,
        /15\s*个\s*工作日内/,
        /§?\s*3[.、]?2|报销时限/,
        /(?:v|版本\s*)?2[.]1/i,
      ),
      evidence(policy.lodging, lodgingRow, /§?\s*2[.、]?1|城市分档/),
    ],
    forbiddenFacts: [/10\s*个\s*工作日/],
  },
  {
    id: 'QA09',
    title: '无证据时拒绝编造',
    question: '差旅早餐可以报销多少元？',
    scope: 'normal',
    role: 'reader',
    kind: 'NO_EVIDENCE',
    expected:
      '样本知识库没有差旅早餐报销额度的依据，应明确说明无法从当前资料确定，不编造金额且不附引用。',
    requiredFacts: [noEvidenceText],
    requiredEvidence: [],
    forbiddenFacts: [
      /\d+\s*(?:元|人民币)/,
      /差旅早餐.{0,12}(?:报销|额度).{0,8}(?:是|为)\s*\d+/,
    ],
  },
  {
    id: 'QA10',
    title: '越权读取限制空间',
    question: restrictedQuestion,
    scope: 'normal',
    role: 'outsider',
    kind: 'NO_EVIDENCE',
    expected: '返回当前可见资料不足，无引用，不披露受限金额、文号或条款内容。',
    requiredFacts: [],
    requiredEvidence: [],
    forbiddenFacts: [
      /FIN-SP-006/i,
      /海外驻点差旅例外|持有效审批单的长期驻场成员/,
      /(?<!\d)873(?!\d)|八百七十三/,
    ],
  },
  {
    id: 'QA11',
    title: 'PDF 行前申请期限',
    question: '普通境内差旅要提前几天申请？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected: '普通差旅须在出发前 3 个工作日提交差旅申请。',
    requiredFacts: [/出发前\s*3\s*个\s*工作日/, /差旅申请/],
    requiredEvidence: [
      evidence(
        policy.guide,
        /出发前\s*3\s*个\s*工作日\s*提交差旅申请/,
        /第\s*1\s*页.*第二条\s*行前申请/,
      ),
    ],
  },
  {
    id: 'QA12',
    title: 'PDF 紧急派工补录期限',
    question:
      '夜间故障紧急派工到了目的地后，多久内补录申请？补录时要关联和注明什么？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected:
      '到达目的地后 24 小时内补录应急差旅申请，并关联服务工单、注明授权人和授权时间。',
    requiredFacts: [
      /到达目的地后/,
      /24\s*小时内/,
      /补录(?:应急差旅)?申请/,
      /服务工单/,
      /授权人.{0,8}授权时间/,
    ],
    requiredEvidence: [
      evidence(
        policy.guide,
        /到达目的地后\s*24\s*小时内补录应急差旅申请/,
        /第\s*2\s*页.*第四条\s*紧急派工/,
      ),
    ],
  },
  {
    id: 'QA13',
    title: 'DOCX 表格同行审批与凭证',
    question: '紧急改签该找谁审批，报销时要附哪些凭证？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected: '审批人为值班运营经理；随单附服务工单及改签差额凭证。',
    requiredFacts: [/值班运营经理/, /服务工单/, /改签差额凭证/],
    requiredEvidence: [
      evidence(
        policy.approvals,
        /紧急改签[\s\S]{0,100}值班运营经理[\s\S]{0,100}服务工单及改签差额凭证/,
        /第二条\s*审批矩阵.*表\s*1\s*第\s*3\s*行/,
      ),
    ],
  },
  {
    id: 'QA14',
    title: 'XLSX 常规住宿限额',
    question: '住宿预算台账里，宁波日常每人每晚的限额是多少？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected: '宁波常规住宿上限为 620 元/人/晚。',
    requiredFacts: [
      /宁波/,
      tableAmount(620),
      /元|人民币/,
      /(?:每人每晚|每人\s*\/\s*晚|人\s*\/\s*晚)/,
    ],
    requiredEvidence: [
      evidence(
        policy.budget,
        /宁波[\s\S]{0,40}620/,
        /工作表[「“"]?住宿限额[」”"]?.{0,12}第\s*4\s*行/,
      ),
    ],
  },
  {
    id: 'QA15',
    title: 'XLSX 节期例外行与条件',
    question: '设备巡检周在杭州可以用多少住宿预算？有什么前置条件？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected: '设备巡检周杭州临时上限为 710 元，行前取得财务复核编号。',
    requiredFacts: [
      /设备巡检周/,
      /杭州/,
      tableAmount(710),
      /元|人民币/,
      /行前|出发前/,
      /财务复核编号/,
    ],
    requiredEvidence: [
      evidence(
        policy.budget,
        /设备巡检周[\s\S]{0,40}杭州[\s\S]{0,40}710[\s\S]{0,50}行前取得财务复核编号/,
        /工作表[「“"]?节期例外[」”"]?.{0,12}第\s*4\s*行/,
      ),
    ],
    forbiddenFacts: [/520\s*(?:元|人民币)/],
  },
  {
    id: 'QA16',
    title: 'PPTX 铁路晚点凭证',
    question: '火车晚点导致改签时，报销要保留哪些材料？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected: '保留铁路晚点证明与平台改签记录。',
    requiredFacts: [/铁路晚点证明/, /平台改签记录/],
    requiredEvidence: [
      evidence(
        policy.training,
        /保留铁路晚点证明与平台改签记录/,
        /第\s*2\s*(?:张)?幻灯片.*表\s*1\s*第\s*2\s*行/,
      ),
    ],
  },
  {
    id: 'QA17',
    title: 'PPTX 原订单退款冲减',
    question:
      '同一段行程的退款从哪里扣减？退款还没到，报销时要附什么、注明什么？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected:
      '退款在原订单项下冲减；未到账时附平台退款回执并注明预计到账日期。',
    requiredFacts: [/原订单项下冲减/, /平台退款回执/, /预计到账日期/],
    requiredEvidence: [
      evidence(policy.training, /在原订单项下冲减/, /第\s*3\s*(?:张)?幻灯片/),
    ],
  },
  {
    id: 'QA18',
    title: 'TXT 电子发票关联数量',
    question: '同一张电子发票可以挂到几张报销单上？',
    scope: 'formats',
    role: 'reader',
    kind: 'ANSWER',
    expected: '一张电子发票只能关联一张报销单。',
    requiredFacts: [/一张电子发票/, /一张报销单/],
    requiredEvidence: [
      evidence(
        policy.settlement,
        /同一张电子发票只能关联一张报销单/,
        /第二条\s*电子发票验收/,
      ),
    ],
  },
  {
    id: 'QA19',
    title: '授权读者访问限制空间',
    question: restrictedQuestion,
    scope: 'normal',
    role: 'reader',
    kind: 'ANSWER',
    expected:
      '驻点津贴为 873 元/人/日，仅适用于持有效审批单的长期驻场成员；驻点起止日期以项目部确认的签到为准。',
    requiredFacts: [
      /873\s*(?:元|人民币)/,
      /人\s*\/\s*[日天]|每人每[日天]/,
      /有效审批单/,
      /长期驻场成员/,
    ],
    requiredEvidence: [
      evidence(
        policy.restricted,
        /873\s*元\/人\/日[\s\S]{0,80}持有效审批单的长期驻场成员/,
        /§?\s*4[.、]?1|高原设备验收专项/,
      ),
    ],
  },
  {
    id: 'QA20',
    title: '无关主题负对照',
    question: '绒冠蜥的保育周期是多少天？',
    scope: 'normal',
    role: 'reader',
    kind: 'NO_EVIDENCE',
    expected:
      '评测合成政策资料不包含相关信息，应明确表示无依据，不编造时长且不附引用。',
    requiredFacts: [noEvidenceText],
    requiredEvidence: [],
    forbiddenFacts: [
      /\d+\s*(?:个\s*)?(?:天|日|工作日|周|个月)/,
      /绒冠蜥.{0,12}(?:保育|保护)周期.{0,8}(?:是|为)\s*\d+/,
    ],
  },
]

function asArray(value) {
  return Array.isArray(value) ? value : []
}

function asText(value) {
  return typeof value === 'string' ? value : ''
}

function matches(pattern, text) {
  if (!(pattern instanceof RegExp)) return false
  return (
    pattern.global || pattern.sticky ? new RegExp(pattern) : pattern
  ).test(text)
}

function patternLabel(pattern) {
  return pattern instanceof RegExp
    ? `/${pattern.source}/${pattern.flags}`
    : String(pattern)
}

/** 判分前去掉 Markdown 强调标记：加粗/代码标记不属于事实差异。 */
function plainText(value) {
  return asText(value)
    .replace(/\*\*|__|`/g, '')
    .replace(/\u200b/g, '')
}

function allParagraphs(observation) {
  return asArray(observation?.paragraphs).filter(
    (paragraph) => paragraph && typeof paragraph === 'object',
  )
}

function getSources(observation, key) {
  return asArray(observation?.[key]).filter(
    (source) => source && typeof source === 'object',
  )
}

function sourceText(source) {
  return [source.name, source.previewText, source.sourceLocation]
    .map(asText)
    .filter(Boolean)
    .join('\n')
}

function sourceHasCode(source, code) {
  const name = asText(source.name).trim().toUpperCase()
  const prefix = asText(code).trim().toUpperCase()
  return (
    Boolean(prefix) &&
    name.startsWith(prefix) &&
    !/^[A-Z0-9]/.test(name.slice(prefix.length))
  )
}

function sourceMatchesRequirement(source, requirement) {
  if (!sourceHasCode(source, requirement.code)) return false
  if (
    requirement.name instanceof RegExp &&
    !matches(requirement.name, asText(source.name))
  ) {
    return false
  }
  if (
    requirement.location instanceof RegExp &&
    !matches(requirement.location, asText(source.sourceLocation))
  ) {
    return false
  }
  return [source.previewText, source.sourceLocation].some((text) =>
    matches(requirement.anchor, asText(text)),
  )
}

function citationRefKey(reference) {
  if (typeof reference === 'string' || typeof reference === 'number')
    return String(reference)
  return (
    asText(reference?.id ?? reference?.citationId) ||
    asText(reference?.name ?? reference?.label)
  )
}

function resolveCitation(reference, citations) {
  const key = citationRefKey(reference)
  if (!key) return null
  const sourceById = citations.find(
    (citation) => asText(citation.id ?? citation.citationId) === key,
  )
  if (sourceById) return sourceById
  // 引用 ID 无法对账时，同名文件不足以证明这条引用归属。
  if ((reference?.id ?? reference?.citationId) != null) return null
  const byName = citations.filter((citation) => asText(citation.name) === key)
  return byName.length === 1 ? byName[0] : null
}

function computeCitationAttribution(testCase, citations, paragraphs) {
  const noEvidence = testCase.kind === 'NO_EVIDENCE'
  const checked =
    noEvidence ||
    paragraphs.some((paragraph) => Array.isArray(paragraph.citations))
  const references = paragraphs.flatMap((paragraph) =>
    asArray(paragraph.citations),
  )
  const resolved = references.map((reference) => ({
    reference,
    citation: resolveCitation(reference, citations),
  }))
  const linked = resolved.flatMap(({ citation }) =>
    citation ? [citation] : [],
  )
  const unresolved = resolved.filter(({ citation }) => !citation)
  const evidence = asArray(testCase.requiredEvidence).map((requirement) => ({
    code: requirement.code,
    anchorPattern: patternLabel(requirement.anchor),
    found: linked.some((citation) =>
      sourceMatchesRequirement(citation, requirement),
    ),
  }))
  const passed = checked
    ? unresolved.length === 0 &&
      (noEvidence
        ? references.length === 0 && citations.length === 0
        : evidence.every((item) => item.found))
    : null

  return {
    checked,
    status: passed === null ? 'UNVERIFIED' : passed ? 'PASSED' : 'FAILED',
    passed,
    paragraphReferences: references.length,
    resolvedReferences: linked.length,
    unresolvedReferences: unresolved.map(
      ({ reference }) => citationRefKey(reference) || null,
    ),
    evidence,
  }
}

function scoreEvidence(testCase, citations) {
  const requirements = asArray(testCase.requiredEvidence)
  const matchesByRequirement = requirements.map((requirement) => {
    const citation = citations.find((item) =>
      sourceMatchesRequirement(item, requirement),
    )
    return {
      code: requirement.code,
      ...(requirement.name instanceof RegExp
        ? { namePattern: patternLabel(requirement.name) }
        : {}),
      locationPattern:
        requirement.location instanceof RegExp
          ? patternLabel(requirement.location)
          : null,
      anchorPattern: patternLabel(requirement.anchor),
      found: Boolean(citation),
      citation: citation
        ? {
            ...(citation.id !== undefined ? { id: citation.id } : {}),
            name: asText(citation.name),
            sourceLocation: asText(citation.sourceLocation),
            ...(citation.documentVersionId !== undefined
              ? { documentVersionId: citation.documentVersionId }
              : {}),
          }
        : null,
    }
  })
  const noEvidenceCase = testCase.kind === 'NO_EVIDENCE'
  const unexpectedCitationCount = noEvidenceCase ? citations.length : 0
  const missing = matchesByRequirement.filter((item) => !item.found)

  return {
    requiredCount: requirements.length,
    matchedCount: matchesByRequirement.length - missing.length,
    missing: missing.map((item) => ({
      code: item.code,
      anchorPattern: item.anchorPattern,
    })),
    unexpectedCitationCount,
    complete: missing.length === 0 && unexpectedCitationCount === 0,
    items: matchesByRequirement,
  }
}

function scoreRetrieval(testCase, candidates) {
  const requirements = asArray(testCase.requiredEvidence)
  if (testCase.kind === 'NO_EVIDENCE') {
    return {
      available: candidates !== null,
      applicable: false,
      score: null,
      recallAt20: null,
      hitAt5: null,
      reason: 'NO_EVIDENCE 题没有预期相关文档，不计算召回率或命中率。',
      candidateCount: candidates?.length ?? null,
    }
  }
  if (requirements.length === 0) {
    return {
      available: candidates !== null,
      applicable: false,
      score: null,
      recallAt20: null,
      hitAt5: null,
      reason: '题目没有定义预期来源。',
      candidateCount: candidates?.length ?? null,
    }
  }
  if (candidates === null) {
    return {
      available: false,
      applicable: true,
      score: null,
      recallAt20: null,
      hitAt5: null,
      reason: '未提供候选明细，检索质量未测。',
      candidateCount: null,
    }
  }

  const top20 = candidates.slice(0, 20)
  const top5 = candidates.slice(0, 5)
  const matchedRequirements = requirements.filter((requirement) =>
    top20.some((candidate) => sourceMatchesRequirement(candidate, requirement)),
  )
  const relevantAt20 = matchedRequirements.length
  const missingAt20 = requirements
    .filter((requirement) => !matchedRequirements.includes(requirement))
    .map((requirement) => requirement.code)
  const recallAt20 = relevantAt20 / requirements.length
  const hitAt5 = requirements.some((requirement) =>
    top5.some((candidate) => sourceMatchesRequirement(candidate, requirement)),
  )
  const score = Math.round(((recallAt20 + Number(hitAt5)) / 2) * 100)

  return {
    available: true,
    applicable: true,
    score,
    recallAt20,
    hitAt5,
    relevantAt20,
    expectedSourceCount: requirements.length,
    missingAt20,
    candidateCount: candidates.length,
  }
}

function scoreForbiddenFacts(testCase, answerText, citations, candidates) {
  const patterns = asArray(testCase.forbiddenFacts)
  const answerFindings = patterns
    .filter((pattern) => matches(pattern, answerText))
    .map(patternLabel)
  const restrictedSources =
    testCase.role === 'outsider' ? [...citations, ...(candidates ?? [])] : []
  const sourceCorpus = restrictedSources
    .map((source) => sourceText(source))
    .join('\n')
  const sourceFindings = patterns
    .filter((pattern) => matches(pattern, sourceCorpus))
    .map(patternLabel)

  return {
    passed: answerFindings.length === 0 && sourceFindings.length === 0,
    answerFindings,
    sourceFindings,
    checkedSourceCount: restrictedSources.length,
  }
}

function answerKinds(paragraphs) {
  return [
    ...new Set(
      paragraphs
        .map((paragraph) => asText(paragraph.kind).toUpperCase())
        .filter(Boolean),
    ),
  ]
}

/**
 * 输入问答终态、段落及其引用、引用详情、可选的有序候选和耗时。
 * 缺失候选时不计检索。候选和引用均按文号、版本、位置和锚点独立核对。
 * 答案事实仅检查字面线索。引用归属核对段落引用与详情的关联。
 */
function scoreAnswer(testCase, observation) {
  const safeCase = testCase && typeof testCase === 'object' ? testCase : {}
  const safeObservation =
    observation && typeof observation === 'object' ? observation : {}
  const paragraphs = allParagraphs(safeObservation)
  const visible = paragraphs.filter(
    (paragraph) => paragraph.status === 'VISIBLE',
  )
  const answerText = plainText(
    visible.map((paragraph) => asText(paragraph.text)).join('\n'),
  )
  const citations = getSources(safeObservation, 'citations')
  const candidates = Array.isArray(safeObservation.candidates)
    ? getSources(safeObservation, 'candidates')
    : null
  const status = asText(safeObservation.status).toUpperCase()
  const kinds = answerKinds(visible)
  const actualKind =
    kinds.length === 1 ? kinds[0] : kinds.length > 1 ? 'MIXED' : null
  const expectedKind = safeCase.kind ?? 'ANSWER'
  const kindMatched = actualKind === expectedKind
  const facts = asArray(safeCase.requiredFacts).map((pattern) => ({
    pattern: patternLabel(pattern),
    found: matches(pattern, answerText),
  }))
  const missingFacts = facts
    .filter((item) => !item.found)
    .map((item) => item.pattern)
  const evidenceScore = scoreEvidence(safeCase, citations)
  const isNoEvidence = safeCase.kind === 'NO_EVIDENCE'
  const citationAttribution = computeCitationAttribution(
    safeCase,
    citations,
    isNoEvidence ? paragraphs : visible,
  )
  const accessCase = safeCase.role === 'outsider'
  const checkedText = accessCase
    ? paragraphs
        .flatMap((paragraph) => [
          asText(paragraph.text),
          ...asArray(paragraph.citations).map(citationRefKey),
        ])
        .join('\n')
    : answerText
  const forbidden = scoreForbiddenFacts(
    safeCase,
    checkedText,
    citations,
    candidates,
  )
  const retrieval = scoreRetrieval(safeCase, candidates)
  const answerChecks = [
    { name: 'terminalStatus', passed: status === 'SUCCEEDED' },
    { name: 'kind', passed: kindMatched },
    { name: 'requiredFacts', passed: missingFacts.length === 0 },
    { name: 'forbiddenFacts', passed: forbidden.passed },
    { name: 'requiredEvidence', passed: evidenceScore.complete },
  ]
  if (citationAttribution.checked) {
    answerChecks.push({
      name: 'citationOwnership',
      passed: citationAttribution.passed === true,
    })
  }
  const passedCount = answerChecks.filter((item) => item.passed).length
  const answerScore =
    status === 'SUCCEEDED'
      ? Math.round((passedCount / answerChecks.length) * 100)
      : 0
  const access = {
    checked: safeCase.role === 'outsider',
    passed: safeCase.role === 'outsider' ? forbidden.passed : null,
    reason:
      safeCase.role === 'outsider'
        ? forbidden.passed
          ? '未在可见答案、引用或候选中发现题目定义的受限内容。'
          : '可见答案、引用或候选包含题目定义的受限内容。'
        : 'reader 角色不执行越权泄漏检查。',
  }
  const elapsedMs =
    typeof safeObservation.elapsedMs === 'number' &&
    Number.isFinite(safeObservation.elapsedMs)
      ? safeObservation.elapsedMs
      : null

  return {
    id: asText(safeCase.id),
    execution: {
      status: status || null,
      failureCode: asText(safeObservation.failureCode) || null,
      terminal: status === 'SUCCEEDED' || status === 'FAILED',
      succeeded: status === 'SUCCEEDED',
      elapsedMs,
    },
    answer: {
      kind: actualKind,
      kinds,
      expectedKind,
      kindMatched,
      visibleParagraphCount: visible.length,
      visibleText: answerText,
    },
    facts: {
      requiredCount: facts.length,
      foundCount: facts.length - missingFacts.length,
      items: facts,
      missing: missingFacts,
      passed: missingFacts.length === 0,
      note: 'RegExp 只检查文字是否出现，不判定数字、单位、条件或关系的真实语义。',
    },
    forbidden,
    evidence: evidenceScore,
    citationOwnership: citationAttribution,
    retrieval,
    access,
    score: {
      retrieval: retrieval.score,
      answer: answerScore,
      answerPassed: answerChecks.every((item) => item.passed),
      answerChecks,
    },
  }
}

export { qualityCases, scoreAnswer }
