'use strict';
// Plain JS client: no build step. All text from the server is inserted with textContent.
const $ = (id) => document.getElementById(id);
const state = { key: '', spaces: [], selected: new Set(), conversationId: null };

function store(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* storage unavailable */ } }
function load(k) { try { return localStorage.getItem(k) || ''; } catch (e) { return ''; } }
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { 'X-API-Key': state.key, ...(opts.headers || {}) } });
  if (!res.ok) {
    let detail = res.status + ' ' + res.statusText;
    try { const p = await res.json(); if (p.detail) detail = p.detail; } catch (e) { /* not json */ }
    throw new Error(detail);
  }
  return res.status === 204 ? null : res.json();
}

async function connect(e) {
  if (e) e.preventDefault();
  state.key = $('api-key').value.trim();
  store('apiKey', state.key);
  try {
    const me = await api('/api/v1/me');
    state.spaces = me.spaces.filter((s) => s !== '*');
    state.selected = new Set(state.spaces);
    renderSpaces();
    refreshDocuments();
  } catch (err) {
    $('spaces').replaceChildren(el('span', 'error', 'Could not connect: ' + err.message));
  }
}

function renderSpaces() {
  const chips = state.spaces.map((s) => {
    const b = el('button', 'chip', s);
    b.type = 'button';
    b.setAttribute('aria-pressed', String(state.selected.has(s)));
    b.onclick = () => {
      state.selected.has(s) ? state.selected.delete(s) : state.selected.add(s);
      b.setAttribute('aria-pressed', String(state.selected.has(s)));
    };
    return b;
  });
  $('spaces').replaceChildren(...chips);
  $('upload-space').replaceChildren(...state.spaces.map((s) => { const o = el('option', '', s); o.value = s; return o; }));
}

async function refreshDocuments() {
  const items = [];
  let pending = false;
  for (const s of state.spaces) {
    const docs = await api(`/api/v1/spaces/${encodeURIComponent(s)}/documents`);
    for (const d of docs) {
      if (d.status === 'SUPERSEDED') continue;
      pending = pending || d.status === 'QUEUED' || d.status === 'INDEXING';
      const li = el('li');
      const name = el('span', 'name', `${s} / ${d.sourceName} v${d.version}`);
      if (d.error) name.title = d.error;
      li.append(name, el('span', 'status ' + d.status, d.status));
      items.push(li);
    }
  }
  $('documents').replaceChildren(...(items.length ? items : [el('li', 'hint', 'No documents yet.')]));
  if (pending) setTimeout(refreshDocuments, 1000);
}

async function upload(e) {
  e.preventDefault();
  const file = $('upload-file').files[0];
  if (!file) return;
  const body = new FormData();
  body.append('file', file);
  try {
    await api(`/api/v1/spaces/${encodeURIComponent($('upload-space').value)}/documents`, { method: 'POST', body });
    $('upload-file').value = '';
    refreshDocuments();
  } catch (err) {
    alert('Upload failed: ' + err.message);
  }
}

function renderAnswerText(container, text) {
  container.replaceChildren();
  const parts = text.split(/(\[\d{1,3}\])/);
  for (const p of parts) {
    const m = /^\[(\d{1,3})\]$/.exec(p);
    if (m) {
      const c = el('span', 'cite', p);
      c.onclick = () => flashSource(Number(m[1]));
      container.append(c);
    } else {
      container.append(document.createTextNode(p));
    }
  }
}

function flashSource(n) {
  const li = document.querySelector(`#sources li[data-n="${n}"]`);
  if (!li) return;
  li.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  li.classList.add('flash');
  setTimeout(() => li.classList.remove('flash'), 1200);
}

function renderSources(sources) {
  if (!sources || !sources.length) {
    $('sources').replaceChildren(el('li', 'hint', 'No sources: the documents did not cover this question.'));
    return;
  }
  $('sources').replaceChildren(...sources.map((s) => {
    const li = el('li', s.cited ? 'cited' : '');
    li.dataset.n = s.number;
    const head = el('div', 'head');
    head.append(el('span', '', `[${s.number}] ${s.sourceName}`), el('span', 'badge', s.score.toFixed(2)));
    const where = [s.space, s.section, s.page ? 'page ' + s.page : null].filter(Boolean).join(' · ');
    li.append(head, el('div', 'where', where), el('div', 'excerpt', s.excerpt));
    return li;
  }));
}

function renderMeta(box, a) {
  const meta = el('div', 'meta');
  const level = a.confidence.level;
  meta.append(el('span', 'badge ' + (level === 'HIGH' ? 'good' : level === 'MEDIUM' ? '' : 'warn'),
    'confidence ' + level.toLowerCase()));
  if (a.verification && a.verification.refusal) {
    meta.append(el('span', 'badge warn', 'declined: not in documents'));
  } else if (a.verification) {
    meta.append(el('span', 'badge ' + (a.verification.grounded ? 'good' : 'bad'),
      a.verification.grounded ? 'verified against sources' : 'not verified'));
  }
  if (a.cached) meta.append(el('span', 'badge', 'cached'));
  const total = Object.values(a.timingsMs || {}).reduce((x, y) => x + y, 0);
  meta.append(el('span', 'badge', total + ' ms'));
  box.append(meta);
  if (a.notice) box.append(el('div', 'notice', a.notice));
  const fb = el('div', 'feedback');
  for (const [label, rating] of [['Helpful', 1], ['Not helpful', -1]]) {
    const b = el('button', '', label);
    b.type = 'button';
    b.onclick = async () => {
      try {
        await api(`/api/v1/answers/${a.answerId}/feedback`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rating }) });
        fb.replaceChildren(el('span', 'hint', 'Thanks for the feedback.'));
      } catch (err) { fb.replaceChildren(el('span', 'error', err.message)); }
    };
    fb.append(b);
  }
  box.append(fb);
}

async function ask(e) {
  e.preventDefault();
  const question = $('question').value.trim();
  if (!question || !state.key) return;
  $('question').value = '';
  const hint = document.querySelector('#messages > .hint');
  if (hint) hint.remove();
  $('messages').append(el('div', 'msg user', question));
  const box = el('div', 'msg bot');
  const text = el('div', 'text', '…');
  box.append(text);
  $('messages').append(box);
  const button = e.submitter || document.querySelector('#ask-form button');
  button.disabled = true;

  let answer = '';
  try {
    const res = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: { 'X-API-Key': state.key, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ question, conversationId: state.conversationId, spaces: [...state.selected] }),
    });
    if (!res.ok) {
      let detail = res.statusText;
      try { detail = (await res.json()).detail || detail; } catch (err) { /* ignore */ }
      throw new Error(detail);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const event = (/^event:(.*)$/m.exec(raw) || [])[1];
        const data = raw.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('\n');
        if (!event || !data) continue;
        const payload = JSON.parse(data);
        if (event === 'meta') {
          state.conversationId = payload.conversationId;
          renderSources(payload.sources);
        } else if (event === 'token') {
          answer += payload.text;
          renderAnswerText(text, answer);
        } else if (event === 'done') {
          renderAnswerText(text, payload.answer);
          renderSources(payload.sources);
          renderMeta(box, payload);
        } else if (event === 'error') {
          throw new Error(payload.message);
        }
      }
      $('messages').scrollTop = $('messages').scrollHeight;
    }
  } catch (err) {
    text.replaceChildren(el('span', 'error', 'Error: ' + err.message));
  } finally {
    button.disabled = false;
  }
}

$('key-form').addEventListener('submit', connect);
$('upload-form').addEventListener('submit', upload);
$('ask-form').addEventListener('submit', ask);
$('question').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('ask-form').requestSubmit(); }
});
$('api-key').value = load('apiKey');
if ($('api-key').value) connect();
