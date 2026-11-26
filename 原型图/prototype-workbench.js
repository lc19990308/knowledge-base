'use strict';

(() => {
  const scope = document.getElementById('search-scope');
  const keyword = document.getElementById('search-keyword');
  const results = document.getElementById('search-results');
  const params = new URLSearchParams(location.search);
  const spaces = Array.from(document.querySelectorAll('[data-space]'));
  const release = window.prototypeRelease;
  let submitted = params.get('q') ?? keyword.value;

  function element(tag, className, text) {
    const node = document.createElement(tag);
    node.className = className;
    if (text) node.textContent = text;
    return node;
  }

  function notice(tone, icon, title, text) {
    const banner = element('div', `notice notice-${tone}`);
    const label = element('span', '');
    label.append(element('strong', '', title), `：${text}`);
    banner.append(element('span', 'n-icon', icon), label);
    return banner;
  }

  for (const space of spaces) {
    const id = space.dataset.space;
    scope.add(new Option(window.prototypeSpaceNames[id], id));
  }
  const selected = params.get('space');
  scope.value = spaces.some((space) => space.dataset.space === selected) ? selected : 'travel';
  keyword.value = submitted;

  function updateOverview() {
    let publishedCount = 0;
    for (const card of spaces) {
      const { space, documents, searchable, group } = card.dataset;
      const published = release?.getRelease(space);
      const available = Boolean(published);
      const indexed = String(published?.searchable ?? searchable);
      const total = String(published?.documents ?? documents);
      if (available) publishedCount++;
      const status = card.querySelector('.sc-head .muted');
      if (status) status.textContent = `${group} · ${available ? '已发布' : space === 'legal' && !release?.wasWithdrawn?.(space) ? '尚未发布' : '不可访问'}`;
      const count = card.querySelector('.sc-stats span:last-child');
      if (count) count.replaceChildren(element('b', '', available ? indexed : '—'), available ? '可检索' : '对用户暂不可见');
      const cardDocumentCount = card.querySelector('.sc-stats span:first-child b');
      if (cardDocumentCount) cardDocumentCount.textContent = total;
      if (space === scope.value) {
        document.getElementById('selected-space-label').textContent = `当前空间：${window.prototypeSpaceNames[space]}`;
        document.getElementById('selected-documents').textContent = total;
        document.getElementById('selected-searchable').textContent = available ? `/ ${indexed}` : '/ —';
      }
    }
    document.getElementById('published-count').textContent = String(publishedCount);
    if (release) {
      const pending = release.list().filter((space) => release.requestStatus(space) === 'pending').length;
      document.getElementById('pending-count').textContent = String(pending);
      release.refreshNav();
    }
  }

  function render() {
    updateOverview();
    results.replaceChildren();
    const space = scope.value;
    const spaceName = window.prototypeSpaceNames[space];
    if (!release?.isAvailable(space)) {
      results.append(notice('fail', '⛔', '空间不可访问', '请切换至已发布且有权限的空间。'));
      return;
    }
    const query = submitted.toLowerCase().trim();
    if (!query) {
      results.append(notice('info', 'ℹ', '空间内搜索', '输入文档名称或正文关键词。'));
      return;
    }
    const docs = window.prototypeDocuments.filter((entry) => entry.space === space && entry.searchable !== false && window.isPrototypeDocumentAvailable(entry));
    const matches = docs.flatMap((entry) => {
      if (entry.title.toLowerCase().includes(query)) return [{ entry, label: '名称匹配' }];
      if (entry.body.toLowerCase().includes(query)) return [{ entry, label: '正文匹配' }];
      return [];
    });
    if (space === 'employee') {
      results.append(notice('warn', '⚠', '处理未完成', '有 1 份文档仍在解析，当前结果可能不完整。'));
    }
    if (matches.length === 0) {
      results.append(notice('info', 'ℹ', '无匹配', space === 'employee' ? '已检索部分没有匹配的文档。' : '此空间没有名称或正文匹配的文档。'));
      return;
    }
    const summary = element('p', 'text-xs muted', `「${spaceName}」内 ${matches.length} 条与「${submitted.trim()}」匹配的演示结果`);
    summary.style.marginBottom = '8px';
    results.append(summary);
    for (const { entry, label } of matches) {
      const item = element('div', 'result-item');
      const title = element('div', 'r-title', entry.title);
      const status = window.prototypeDocumentStatus(entry);
      title.append(' ', element('span', `badge badge-${status.tone}`, status.label));
      const metadata = element('div', 'r-meta');
      const link = element('a', '', '查看文档');
      link.href = `document.html?doc=${encodeURIComponent(entry.id)}`;
      metadata.append(element('span', '', label), element('span', '', `更新 ${entry.date}`), element('span', 'grow'), link);
      item.append(title, element('p', 'r-snip', entry.body), metadata);
      results.append(item);
    }
  }

  function submit(text) {
    submitted = text;
    keyword.value = text;
    render();
  }
  document.getElementById('space-search').addEventListener('submit', (event) => {
    event.preventDefault();
    submit(keyword.value);
  });
  document.getElementById('top-search').addEventListener('submit', (event) => {
    event.preventDefault();
    const input = event.currentTarget.querySelector('input');
    submit(input.value);
    document.getElementById('space-search').scrollIntoView({ block: 'nearest' });
  });
  scope.addEventListener('change', () => submit(keyword.value));
  window.addEventListener('prototype-release-change', render);
  window.addEventListener('pageshow', render);
  render();
})();
