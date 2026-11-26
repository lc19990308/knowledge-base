'use strict';

(() => {
  const conversation = document.getElementById('conversation');
  const question = document.getElementById('question');
  const form = document.getElementById('question-form');
  const availableSpace = document.getElementById('available-space');
  const noAvailableSpace = document.getElementById('no-available-space');
  const maskedText = '此段依赖已撤回的来源，内容和引用均不可查看。';
  let historicalExample = false;

  function node(tag, className, text) {
    const element = document.createElement(tag);
    element.className = className;
    if (text) element.textContent = text;
    return element;
  }

  function syncVisibility() {
    const available = Boolean(window.prototypeRelease?.isAvailable('travel'));
    availableSpace.hidden = !available;
    availableSpace.style.display = available ? '' : 'none';
    noAvailableSpace.hidden = available;
    const priorPublication = window.prototypeRelease?.wasWithdrawn?.('travel');
    if (!available || priorPublication) {
      const affected = available ? '[data-travel-evidence="fixed"]' : '[data-travel-evidence]';
      for (const answer of conversation.querySelectorAll(affected)) {
        answer.replaceChildren(
          node('span', 'ans-flag af-expired', '⛔ 部分内容已屏蔽（来源已撤回）'),
          node('p', 'muted', maskedText),
        );
      }
    }
    conversation.hidden = false;
  }

  function addMessage(side, text, flag, source) {
    const message = node('div', `msg msg-${side}`);
    const body = node('div', 'm-body');
    const bubble = node('div', 'm-bubble');
    if (flag) bubble.append(node('span', 'ans-flag af-none', flag));
    bubble.append(node('p', '', text));
    if (source) {
      bubble.dataset.travelEvidence = '';
      const citation = node('a', 'cite-item', source.title);
      citation.href = `document.html?doc=${encodeURIComponent(source.id)}`;
      const citations = node('div', 'cite-block');
      citations.append(node('div', 'cite-label', '引用文档（1）'), citation);
      bubble.append(citations);
    }
    body.append(bubble, node('div', 'm-time', '模拟问答 · 不调用真实模型'));
    message.append(node('span', 'm-avatar', side === 'ai' ? 'AI' : '超'), body);
    conversation.append(message);
  }

  for (const control of document.querySelectorAll('[data-message]')) {
    function openMessage() {
      for (const item of document.querySelectorAll('[data-message]')) {
        const current = item === control;
        item.classList.toggle('on', current);
        item.setAttribute('aria-pressed', String(current));
      }
      historicalExample = control.dataset.message === 'masked-answer';
      document.getElementById(control.dataset.message)?.scrollIntoView({ block: 'start' });
    }
    control.addEventListener('click', openMessage);
    control.addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      openMessage();
    });
  }

  document.getElementById('new-question').addEventListener('click', () => {
    historicalExample = false;
    question.value = '';
    question.focus();
  });
  question.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
    event.preventDefault();
    form.requestSubmit();
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const text = question.value.trim();
    if (!text) return;
    addMessage('user', text);
    const available = window.prototypeRelease?.isAvailable('travel');
    const candidates = window.prototypeDocuments.filter((entry) => window.isPrototypeDocumentAvailable(entry));
    const source = candidates.find((entry) => entry.id === 'travel-v4') ?? candidates.find((entry) => entry.id === 'travel-v3');
    if (!available || historicalExample || /过渡期|撤回|第\s*6\.2\s*条/.test(text)) {
      addMessage('ai', '当前授权且已发布的资料没有可引用的依据。旧引用及已撤回的内容不会用于本次回答。', '◌ 无依据');
    } else if (/报销时限|发票要求|多久报销|何时报销/.test(text) && source) {
      addMessage('ai', source.body, '', source);
    } else {
      addMessage('ai', '当前演示资料未找到可支持回答的依据。', '◌ 无依据');
    }
    question.value = '';
    conversation.scrollTop = conversation.scrollHeight;
    question.focus();
  });
  window.addEventListener('prototype-release-change', syncVisibility);
  window.addEventListener('pageshow', syncVisibility);
  syncVisibility();
})();
