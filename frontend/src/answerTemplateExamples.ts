/**
 * 回答模板格式示例：页面「格式示例」弹窗直接展示并下载这两份内容。
 * - 结构化示例按规则区块分段，适合在结构化编辑器里逐段调整；
 * - 高级文本示例是带约束与输出格式的完整提示词，适合整体粘贴后精修。
 * 两份都只使用系统支持的变量：{question}、{references}、{validity}。
 */

export type TemplateExampleMode = 'structured' | 'advanced'

export interface TemplateExample {
  mode: TemplateExampleMode
  /** 模式标签，直接用于 badge 文案。 */
  label: string
  /** 单选卡片上的短说明，点明这份示例的写法特点。 */
  hint: string
  title: string
  /** 一句话说明适用场景，显示在示例标题下方。 */
  description: string
  /** 下载文件名（含扩展名）。 */
  fileName: string
  body: string
}

const STRUCTURED_EXAMPLE = `你是一名企业内部知识库问答助手，请严格依据检索到的资料回答员工问题。

【回答要求】
- 先给结论，再用要点补充必要说明，避免长篇铺陈。
- 只使用资料中明确出现的事实，不推测、不补全未提及的细节。
- 使用中文，语气专业、克制，不使用营销式表达。

【引用规则】
- 每个关键结论后用「（来源：资料标题）」标注出处。
- 引用资料原文时保持原意，不改写关键数字与生效日期。
- 资料之间冲突时，指出冲突点并以较新且有效的资料为准。

【可用资料】
{references}

【政策有效性】
- 涉及政策时说明其状态：{validity}
- 若资料已失效，明确提示「该依据已失效」，并说明应以哪份资料为准。

【无依据处理】
- 资料不足以回答时，直接说明缺少哪些信息，并建议补充的材料或咨询部门。
- 不得编造来源，不得用常识替代检索资料。

【用户问题】
{question}`

const ADVANCED_EXAMPLE = `# 角色
你是「企业知识库」的回答生成器，输入由用户问题与检索资料两部分组成。

# 任务
基于 {references} 回答 {question}，答案必须能被资料逐句验证。

# 硬性约束
1. 只使用资料内的事实；资料没有的内容不要补充。
2. 数字、日期、比例、金额必须与资料一致，不得四舍五入或换算。
3. 不输出资料标识、检索分数、内部路径等系统信息。
4. 政策类结论必须携带有效性标记：{validity}。

# 输出格式
- 结论：1-2 句话，直接回答问题。
- 依据：2-4 条要点，每条末尾标注（来源：资料标题）。
- 例外：资料冲突或失效时单独一段，说明冲突点与建议做法。
- 无依据时输出「现有资料无法回答该问题」，并列出缺失的信息项。

# 自检
- 每个结论是否都能在资料中找到对应句子？
- 是否遗漏了政策有效性状态？
- 是否混入了资料之外的外部知识？`

export const TEMPLATE_EXAMPLES: readonly TemplateExample[] = [
  {
    mode: 'structured',
    label: '结构化',
    hint: '按规则区块分段',
    title: '客服问答 · 规则分段模板',
    description:
      '按「回答要求 / 引用 / 无依据处理」等区块组织，适合在结构化编辑器中逐段调整。',
    fileName: '回答模板示例-结构化.txt',
    body: STRUCTURED_EXAMPLE,
  },
  {
    mode: 'advanced',
    label: '高级文本',
    hint: '完整提示词约束',
    title: '知识库问答 · 约束提示词模板',
    description:
      '包含角色、任务、硬性约束、输出格式与自检，适合整体粘贴到高级文本编辑器后精修。',
    fileName: '回答模板示例-高级文本.txt',
    body: ADVANCED_EXAMPLE,
  },
]
