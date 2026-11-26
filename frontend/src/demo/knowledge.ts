export interface DemoSpace {
  readonly id: string
  readonly name: string
  readonly documents: number
  readonly searchable: number
  readonly processing: number
}

export interface DemoDocument {
  readonly id: string
  readonly spaceId: string
  readonly title: string
  readonly body: string
}

export const demoSpaces: readonly [DemoSpace, ...DemoSpace[]] = [
  {
    id: 'employee',
    name: '员工手册',
    documents: 3,
    searchable: 2,
    processing: 1,
  },
  {
    id: 'travel',
    name: '差旅报销制度',
    documents: 2,
    searchable: 2,
    processing: 0,
  },
  {
    id: 'safety',
    name: '安全生产规范',
    documents: 1,
    searchable: 1,
    processing: 0,
  },
]

export const demoDocuments: readonly DemoDocument[] = [
  {
    id: 'attendance',
    spaceId: 'employee',
    title: '考勤管理办法.pdf',
    body: '员工可在每月最后一个工作日前提交考勤补卡申请。',
  },
  {
    id: 'leave',
    spaceId: 'employee',
    title: '休假管理细则.docx',
    body: '年度休假由员工在系统内申请，直属负责人审核。',
  },
  {
    id: 'travel-rules',
    spaceId: 'travel',
    title: '差旅报销管理办法.pdf',
    body: '出差结束后十个工作日内提交报销申请，并附行程与票据。',
  },
  {
    id: 'travel-bills',
    spaceId: 'travel',
    title: '票据登记指引.docx',
    body: '电子发票须填写票据号码并上传原件。',
  },
  {
    id: 'incident',
    spaceId: 'safety',
    title: '现场告警处理指引.pdf',
    body: '发现现场设备告警，应立即通知值班人员并记录发生时间。',
  },
]
