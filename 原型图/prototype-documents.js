'use strict';

// Shared synthetic documents for the standalone HTML prototype, not an access-control boundary.
window.prototypeDocuments = Object.freeze([
  { id: 'travel-v3', space: 'travel', title: '差旅费用报销管理办法-v3.pdf', status: '现行 v3', tone: 'published', date: '2026-11-05', body: '国内差旅报销时限：行程结束后 15 个工作日内提交申请。发票应为增值税普通发票或专用发票，抬头为公司全称；住宿费附入住水单。一线城市住宿上限为 450 元/晚。' },
  { id: 'travel-v2', space: 'travel', title: '差旅费用报销管理办法.pdf', status: '已失效 v2', tone: 'warn', date: '2026-10-31', body: '2024 版历史规定：一线城市住宿上限 400 元/晚。本版于 2026-10-31 失效，不能作现行依据；后续由 v3 取代。' },
  { id: 'cross-border', space: 'travel', title: '跨境差旅补充规定.docx', status: '有效性待核实', tone: 'info', date: '2026-10-30', body: '草案提到跨境差旅报销时限可延长至 20 个工作日，以票据日期为起点。当前无法核实该文件是否生效，不能据此确定现行时限。' },
  { id: 'travel-subsidy', space: 'travel', title: '差旅补贴实施细则.pdf', status: '现行', tone: 'published', date: '2026-09-01', body: '一线城市住宿补贴标准为 500 元/晚。本细则与差旅费用报销管理办法 v3 的 450 元/晚存在冲突，适用顺序需向发布部门确认。' },
  { id: 'travel-process', space: 'travel', title: '2026 版报销审批流程图.pdf', status: '现行', tone: 'published', date: '2026-11-02', body: '按报销时限提交材料后，系统依次流转至直属负责人和财务复核。' },
  { id: 'travel-scan', space: 'travel', title: '历史报销单据样式表.pdf', status: '无可提取正文', tone: 'draft', date: '2026-09-06', body: '', searchable: false },
  { id: 'travel-v4', space: 'travel', title: '差旅费用报销管理办法-v4.pdf', status: '现行 v4', tone: 'published', date: '2026-11-20', body: '国内差旅报销时限：行程结束后 15 个工作日内提交申请。发票抬头为公司全称；电子票据需随报销单上传。一线城市住宿上限为 450 元/晚。', requiresPublishedChange: true },
  { id: 'employee-attendance', space: 'employee', title: '员工考勤与试用期管理办法.pdf', status: '现行', tone: 'published', date: '2026-11-05', body: '考勤更正须在当月最后一个工作日提交。试用期考核包括工作质量、岗位技能及出勤记录。' },
  { id: 'safety-checklist', space: 'safety', title: '安全生产评审材料清单.pdf', status: '现行', tone: 'published', date: '2026-11-01', body: '安全生产评审需要准备风险清单、检查记录及整改说明。' },
  { id: 'contract-template', space: 'contract', title: '对外合作协议模板.docx', status: '现行', tone: 'published', date: '2026-11-08', body: '对外合作协议应明确各方权利义务、交付验收和争议解决方式。' },
  { id: 'product-release', space: 'product', title: '产品发布流程说明.pdf', status: '现行', tone: 'published', date: '2026-11-11', body: '产品发布前完成质量审查，登记发布窗口及回滚负责人。' },
  { id: 'legal-checklist', space: 'legal', title: '合规审查材料清单.pdf', status: '现行', tone: 'published', date: '2026-11-20', body: '提交材料前核对政策适用范围和有效日期，并记录审查负责人。' },
]);

window.prototypeSpaceNames = Object.freeze({
  employee: '员工手册', travel: '差旅报销制度', safety: '安全生产规范',
  legal: '合规与风控政策', contract: '对外合作协议模板', product: '产品发布流程',
});

function hasPublishedChange(release, filename) {
  return release.changes.some((change) => change.name === filename);
}

window.prototypeDocumentStatus = (entry) => {
  const release = window.prototypeRelease?.getRelease(entry.space);
  if (entry.id === 'travel-v3' && release && hasPublishedChange(release, window.prototypeRelease.FILES.v4)) {
    return { label: '历史 v3', tone: 'draft' };
  }
  return { label: entry.status, tone: entry.tone };
};

window.isPrototypeDocumentAvailable = (entry) => {
  if (!entry) return false;
  const release = window.prototypeRelease?.getRelease(entry.space);
  if (!release) return false;
  return !entry.requiresPublishedChange || hasPublishedChange(release, entry.title);
};
