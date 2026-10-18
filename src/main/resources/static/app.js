const messages = document.querySelector('#messages');
const input = document.querySelector('#question');
const send = document.querySelector('#send');
const form = document.querySelector('#chat-form');
const node = (tag, text, cls) => { const el = document.createElement(tag); if (text) el.textContent = text; if (cls) el.className = cls; return el; };
async function status() {
  try {
    const response = await fetch('/api/status'); if (!response.ok) throw new Error();
    const s = await response.json();
    document.querySelector('#status').textContent = `公开条目 ${s.publicEntries} · JDK ${s.java}\n检索 ${s.embedding} · 路由 ${s.routing}\n回答 ${s.generation}`;
    document.querySelector('#dataset').textContent = s.demoData ? '已加载虚构示例，不代表真实用户；也可能包含本地批准资料。' : '示例已关闭；仅使用本地明确批准的资料。';
  } catch { document.querySelector('#status').textContent = '暂时无法连接，请检查本地服务。'; }
}
const chatHistory = [];
async function ask(question) {
  if (send.disabled || !question.trim()) return;
  messages.append(node('article', question, 'turn user'));
  const turn = node('article', '', 'turn assistant');
  turn.append(node('div', '正在查阅公开资料…', 'answer')); messages.append(turn);
  send.disabled = true; send.textContent = '查阅中…'; input.value = '';
  try {
    const response = await fetch('/api/chat', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({question, history: chatHistory.slice(-10)})});
    const data = await response.json(); if (!response.ok) throw new Error(data.error || '请求失败，请重试。');
    turn.replaceChildren(node('div', data.answer, 'answer'));
    if (data.skills?.length) {
      const grid = node('div', '', 'skills');
      for (const skill of data.skills) {
        const card = node('div', '', 'skill'); card.append(node('strong', skill.name));
        for (const text of [skill.level, skill.boundaries, '证据：' + skill.evidence.join('；'), '核对日期：' + skill.updatedAt]) if (text) card.append(node('p', text));
        grid.append(card);
      }
      turn.append(grid);
    }
    const metaParts = [`${data.mode} · 路由 ${data.routing.provider} / ${data.routing.intent}`];
    if (data.routing.explanation) metaParts.push(`[${data.routing.explanation}]`);
    metaParts.push(`· 检索 ${data.embeddingMode}`);
    if (data.resolvedQuestion) metaParts.push(`· 消解: ${data.resolvedQuestion}`);
    turn.append(node('div', metaParts.join(' '), 'meta'));
    if (data.sources?.length) {
      const details = node('details', '', 'sources'); details.append(node('summary', `查看 ${data.sources.length} 条来源`));
      for (const source of data.sources) {
        const item = node('div', '', 'source');
        item.append(node('strong', `[${source.number}] ${source.title}`), node('p', `${source.origin} / ${source.locator} / ${source.updatedAt}`), node('p', source.excerpt)); details.append(item);
      }
      turn.append(details);
    }
    chatHistory.push({role: 'user', text: question});
    chatHistory.push({role: 'assistant', text: data.answer});
  } catch (err) { turn.replaceChildren(node('div', err.message || '连接失败，请重试。', 'answer')); }
  finally { send.disabled = false; send.textContent = '发送 ↑'; input.focus(); turn.scrollIntoView({block:'nearest', behavior:'smooth'}); }
}
form.addEventListener('submit', event => {event.preventDefault(); ask(input.value);});
input.addEventListener('keydown', event => {if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {event.preventDefault(); form.requestSubmit();}});
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => ask(button.dataset.question)));
status();
