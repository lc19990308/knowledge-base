(() => {
  'use strict';

  const STORAGE_KEY = 'knowledge-base.prototype-release.v1';
  const CHANGE_EVENT = 'prototype-release-change';
  const ADMIN = '刘超';
  const FILES = Object.freeze({
    v2: '差旅费用报销管理办法.pdf', v3: '差旅费用报销管理办法-v3.pdf', v4: '差旅费用报销管理办法-v4.pdf',
    crossBorder: '跨境差旅补充规定.docx', subsidy: '差旅补贴实施细则.pdf', scan: '历史报销单据样式表.pdf',
    rates: '2026 差旅住宿标准表.xlsx', tips: '境外差旅安全提示.md', archive: '报销单据扫描归档.pdf'
  });
  const STATUS = Object.freeze({
    pending: ['待审核', 'badge-review'], rejected: ['已驳回', 'badge-rejected'],
    published: ['已发布', 'badge-published'], withdrawn: ['已撤回', 'badge-draft'], draft: ['草稿', 'badge-draft']
  });
  const change = (name, kind, detail) => ({ name, kind, detail });
  const request = (reason, at, changes, outcome = null, id = at) => ({ id, reason, at, changes, outcome });
  const outcome = (decision, reasons, at) => ({ decision, reasons, at, by: ADMIN });
  const release = (revision, at, documents, searchable, changes = []) => ({ revision, at, documents, searchable, changes });
  const space = (id, name, team, owner, created, documents, searchable, maintain = true, initial = {}) => ({
    id, name, team, owner, created, documents, searchable, maintain,
    modified: created, release: null, request: null, withdrawal: null, withdrawnBefore: false, ...initial
  });
  const seeds = [
    space('travel', '差旅报销制度', 'finance', '王敏', '2026-08-04', 48, 47, true, {
      modified: '2026-11-18', release: release('v3', '2026-11-05', 48, 47),
      request: request('修订差旅费用报销管理办法，待确认新版政策。', '2026-11-18 14:20', [change(FILES.v4, 'revision', '修订 v3→v4')])
    }),
    space('legal', '合规与风控政策', 'legal', '李工', '2026-09-18', 24, 24, true, {
      modified: '2026-11-17', request: request('申请首次发布 24 份合规与风控政策文档。', '2026-11-17 09:05',
        [change('合规与风控政策文档（24 份）', 'metadata', '首次发布')])
    }),
    space('vendor', '供应商准入资料', 'procurement', '张远', '2026-10-02', 16, 16, true, {
      modified: '2026-11-15', request: request('申请首次发布 16 份供应商准入资料。', '2026-11-14 10:12',
        [change('供应商准入资料（16 份）', 'metadata', '首次发布')], outcome('rejected', [
          '缺少政策生效日期标注：9 份文档未标注生效/失效日期，无法核实政策有效性。',
          '正文提取质量：2 份 Word 文档表格内容解析缺失，需修复后重新上传。'
        ], '2026-11-15 16:40'))
    }),
    space('contract', '对外合作协议模板', 'legal', '法务组', '2026-07-11', 89, 88, false, {
      modified: '2026-11-08', release: release('v1', '2026-11-08', 89, 88)
    }),
    space('employee', '员工手册', 'hr', '人事组', '2026-07-10', 62, 61, false, {
      modified: '2026-11-05', release: release('v1', '2026-11-05', 62, 61)
    }),
    space('safety', '安全生产规范', 'ops', '安全组', '2026-08-12', 35, 35, false, {
      modified: '2026-11-10', release: release('v1', '2026-11-10', 35, 35)
    }),
    space('product', '产品发布流程', 'pmo', '项目组', '2026-08-09', 90, 90, false, {
      modified: '2026-11-11', release: release('v1', '2026-11-11', 90, 90)
    }),
    space('historical-withdrawal', '旧版采购审批指引', 'procurement', '采购组', '2026-06-13', 8, 8, false, {
      modified: '2026-11-09', release: release('v1', '2026-08-12', 8, 8),
      withdrawal: { at: '2026-11-09 15:10', by: ADMIN }
    })
  ];

  /** @param {unknown} value */
  const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  const strings = (value, keys) => keys.every((key) => typeof value[key] === 'string');
  const counts = (value) => ['documents', 'searchable'].every((key) => Number.isSafeInteger(value[key]) && value[key] >= 0)
    && value.searchable <= value.documents;
  const validChange = (value) => record(value) && strings(value, ['name', 'kind', 'detail'])
    && ['revision', 'new', 'archive', 'metadata'].includes(value.kind);
  const validChanges = (value) => Array.isArray(value) && value.every(validChange);
  const validDecision = (value) => ['approved', 'rejected'].includes(value);
  const validRequest = (value) => record(value) && strings(value, ['reason', 'at']) && validChanges(value.changes)
    && (value.outcome === null || (record(value.outcome) && strings(value.outcome, ['at', 'by'])
      && validDecision(value.outcome.decision) && Array.isArray(value.outcome.reasons)
      && value.outcome.reasons.every((reason) => typeof reason === 'string')));
  const validRelease = (value) => record(value) && strings(value, ['revision', 'at']) && /^v[1-9]\d*$/.test(value.revision)
    && counts(value) && validChanges(value.changes);
  const validSpace = (value) => record(value) && strings(value, ['id', 'name', 'team', 'owner', 'created', 'modified'])
    && /^[a-z0-9-]+$/.test(value.id) && typeof value.maintain === 'boolean' && counts(value)
    && (value.release === null || validRelease(value.release)) && (value.request === null || validRequest(value.request))
    && (value.withdrawal === null || (record(value.withdrawal) && strings(value.withdrawal, ['at', 'by'])));

  let memory = null;
  let storageAvailable = true;
  function list() {
    let encoded = memory;
    if (storageAvailable) {
      try { encoded = localStorage.getItem(STORAGE_KEY); } catch { storageAvailable = false; }
    }
    if (encoded) {
      try {
        /** @type {unknown} */
        const saved = JSON.parse(encoded);
        if (Array.isArray(saved) && saved.every(validSpace) && new Set(saved.map((entry) => entry.id)).size === saved.length
          && seeds.every((seed) => saved.some((entry) => entry.id === seed.id))) return saved;
      } catch { /* Malformed demo records fall back to fixtures. */ }
    }
    return JSON.parse(JSON.stringify(seeds));
  }
  function notify() { window.dispatchEvent(new Event(CHANGE_EVENT)); }
  function save(spaces) {
    memory = JSON.stringify(spaces);
    if (storageAvailable) {
      try { localStorage.setItem(STORAGE_KEY, memory); } catch { storageAvailable = false; }
    }
    notify();
  }
  function update(id, transform) {
    const spaces = list();
    const before = spaces.find((entry) => entry.id === id);
    const after = before && transform(before);
    if (!after) return false;
    save(spaces.map((entry) => entry.id === id ? after : entry));
    return true;
  }
  window.addEventListener('storage', (event) => {
    if (event.key === STORAGE_KEY || event.key === null) notify();
  });

  const stamp = () => new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short'
  }).format(new Date());
  const getSpace = (id) => list().find((entry) => entry.id === id);
  const getRelease = (id) => {
    const entry = getSpace(id);
    return entry && !entry.withdrawal ? entry.release : null;
  };
  const nextRevision = (entry) => entry.release ? `v${Number(entry.release.revision.slice(1)) + 1}` : 'v1';
  const requestStatus = (entry) => !entry.request ? null : (entry.request.outcome?.decision ?? 'pending');
  const knowledgeStatus = (entry) => entry.withdrawal ? 'withdrawn' : requestStatus(entry) === 'pending' ? 'pending'
    : requestStatus(entry) === 'rejected' ? 'rejected' : entry.release ? 'published' : 'draft';

  function create(name, team) {
    const cleanName = name.trim();
    const cleanTeam = team.trim();
    const spaces = list();
    if (!cleanName || !cleanTeam || spaces.some((entry) => entry.name === cleanName)) return null;
    const entry = space(`space-${Date.now()}-${spaces.length}`, cleanName, cleanTeam, '提交用户', stamp().slice(0, 10), 0, 0);
    save([...spaces, entry]);
    return entry;
  }
  function knownFilenames(entry, includeRequest = true) {
    const names = entry.id === 'travel' ? [FILES.v2, FILES.v3, FILES.crossBorder, FILES.subsidy, FILES.scan] : [];
    const staged = includeRequest && requestStatus(entry) !== 'approved' ? entry.request?.changes ?? [] : [];
    return new Set([...names, ...[...(entry.release?.changes ?? []), ...staged]
      .filter((item) => item.kind !== 'metadata').map((item) => item.name)]);
  }
  function submit(id, reason, changes = []) {
    const text = reason.trim();
    if (!text || !validChanges(changes)) return false;
    return update(id, (entry) => {
      if (!entry.maintain) return null;
      const at = stamp();
      const known = knownFilenames(entry, false);
      const pending = requestStatus(entry) !== 'approved' ? entry.request?.changes ?? [] : [];
      const merged = new Map(pending.map((item) => [item.name, item]));
      for (const item of changes) {
        if (!known.has(item.name) && !merged.has(item.name)) merged.set(item.name, item);
      }
      if (changes.length && merged.size === pending.length) return null;
      if (!changes.length) merged.set('空间资料与政策状态', change('空间资料与政策状态', 'metadata', text));
      const requestId = `${id}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      return { ...entry, modified: at.slice(0, 10), request: request(text, at, [...merged.values()], null, requestId) };
    });
  }
  function review(id, decision, reason, expected) {
    const text = reason.trim();
    if (!validDecision(decision) || (decision === 'rejected' && !text)) return false;
    return update(id, (entry) => {
      if (!entry.request || entry.request.outcome || JSON.stringify(entry.request) !== expected) return null;
      const at = stamp();
      const current = entry.request;
      const resolved = { ...entry, request: { ...current, outcome: outcome(decision, [text], at) }, modified: at.slice(0, 10) };
      if (decision === 'rejected') return resolved;
      const additions = current.changes.filter((item) => item.kind !== 'metadata');
      const documents = entry.documents + additions.length;
      const searchable = entry.searchable + additions.filter((item) => item.kind !== 'archive').length;
      return {
        ...resolved, withdrawal: null, documents, searchable,
        release: release(nextRevision(entry), at, documents, searchable, [...(entry.release?.changes ?? []), ...additions])
      };
    });
  }
  function withdraw(id, revision) {
    return update(id, (entry) => {
      if (!entry.release || entry.withdrawal || entry.release.revision !== revision) return null;
      const at = stamp();
      return {
        ...entry, modified: at.slice(0, 10), withdrawal: { at, by: ADMIN }, withdrawnBefore: true,
        request: requestStatus(entry) === 'pending' ? null : entry.request
      };
    });
  }

  function element(tag, className = '', text = '') {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }
  function badge(label, variant, dot = false) {
    const node = element('span', `badge ${variant}`);
    if (dot) node.append(element('span', 'dot'));
    node.append(label);
    return node;
  }
  function link(label, href, className = 'btn btn-sm') {
    const node = element('a', className, label);
    node.href = href;
    return node;
  }
  function setHash(hash = '') {
    history.replaceState(null, '', `${location.pathname}${location.search}${hash}`);
  }
  function refreshNav() {
    const count = list().filter((entry) => requestStatus(entry) === 'pending').length;
    const nav = document.querySelector('.nav-link[href="publish.html"] .nav-badge');
    if (nav) {
      nav.textContent = String(count);
      nav.hidden = count === 0;
    }
  }
  window.prototypeRelease = Object.freeze({
    FILES, STATUS, CHANGE_EVENT, list, getSpace, getRelease, isAvailable: (id) => !!getRelease(id),
    wasWithdrawn: (id) => { const entry = getSpace(id); return !!entry && (!!entry.withdrawal || entry.withdrawnBefore === true); },
    knowledgeStatus, requestStatus, nextRevision, create, submit, review, withdraw, knownFilenames,
    element, badge, link, setHash, refreshNav
  });
})();
