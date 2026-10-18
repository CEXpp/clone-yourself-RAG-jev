/**
 * 证据桌 (Evidence Desk) · 前端交互逻辑
 *
 * 遵循设计规范：
 * 1. 原生 JavaScript 实现，零外部依赖，严格遵循 CSP；
 * 2. 所有服务端与模型文本一律使用 textContent / createTextNode 渲染，杜绝 innerHTML XSS 风险；
 * 3. 采用 POST + fetch() ReadableStream 读取 SSE 进度流（非 EventSource）；
 * 4. 模块化分为四个清晰小节：
 *    - SECTION 1: 状态与生命周期 (State & Lifecycle)
 *    - SECTION 2: 流式传输与 SSE 解析 (Streaming & SSE Parser)
 *    - SECTION 3: 证据桌 DOM 渲染 (Rendering Engine)
 *    - SECTION 4: 检索透视与交互联动 (Retrieval Lens & Interactions)
 */

(() => {
  'use strict';

  // ==========================================================================
  // SECTION 1: 状态与生命周期 (State & Lifecycle)
  // ==========================================================================

  // DOM 节点引用
  const messagesEl = document.querySelector('#messages');
  const questionInput = document.querySelector('#question');
  const sendBtn = document.querySelector('#send');
  const chatForm = document.querySelector('#chat-form');
  const resetBtn = document.querySelector('#reset-btn');

  // 状态显示节点
  const statEntries = document.querySelector('#stat-entries');
  const statEmbedding = document.querySelector('#stat-embedding');
  const statRouting = document.querySelector('#stat-routing');
  const statGeneration = document.querySelector('#stat-generation');
  const demoNoticeText = document.querySelector('#demo-notice-text');

  // 客户端多轮对话历史：[{ role: 'user', text: '...' }, { role: 'assistant', text: '...' }]
  const chatHistory = [];
  let turnCounter = 0;
  let activeAbortController = null;

  /**
   * DOM 元素安全构造工具函数（禁止使用 innerHTML 写入不可信文本）
   */
  function el(tag, className, textContent) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (textContent !== undefined && textContent !== null) node.textContent = textContent;
    return node;
  }

  /**
   * 获取并呈现本地后端服务状态 (/api/status)
   */
  async function loadStatus() {
    try {
      const res = await fetch('/api/status');
      if (!res.ok) throw new Error('status fetch failed');
      const s = await res.json();

      if (statEntries) statEntries.textContent = `${s.publicEntries} 条`;
      if (statEmbedding) statEmbedding.textContent = s.embedding || '—';
      if (statRouting) statRouting.textContent = s.routing === 'jev-enabled' ? '远程JEV' : '本地保守意图';
      if (statGeneration) statGeneration.textContent = s.generation === 'http-enabled' ? '大模型生成' : '本地公开摘录';

      if (demoNoticeText) {
        demoNoticeText.textContent = s.demoData
          ? '当前加载虚构示例（陈屿，Java 后端求职者），仅限公开批准资料，不代表真实用户。'
          : '示例数据已关闭；当前仅使用本地明确批准的公开资料。';
      }
    } catch (err) {
      if (statEntries) statEntries.textContent = '无法连接';
      if (statEmbedding) statEmbedding.textContent = '离线';
      if (statRouting) statRouting.textContent = '—';
      if (statGeneration) statGeneration.textContent = '—';
    }
  }

  /**
   * 重置新对话 (清空历史与消息列表，恢复初始欢迎卡)
   */
  function resetConversation() {
    if (activeAbortController) {
      activeAbortController.abort();
      activeAbortController = null;
    }
    chatHistory.length = 0;
    messagesEl.replaceChildren();

    // 重新创建欢迎卡
    const welcomeSheet = el('article', 'desk-sheet welcome-sheet');
    welcomeSheet.id = 'welcome-card';
    const seal = el('div', 'sheet-seal', '◈');
    const body = el('div', 'welcome-body');
    const heading = el('h2', 'welcome-heading', '欢迎查阅个人数字分身资料档案');
    const lead = el(
      'p',
      'welcome-lead',
      '这是一个严谨遵循事实依据的数字分身问答系统。每一句回答均基于本地已批准公开的技能卡、项目卡与笔记文档；缺少充分依据时将坦诚拒答，杜绝编造。'
    );
    const tray = el('div', 'example-tray');
    tray.append(el('span', 'tray-label', '从预设示范问题开始：'));
    const chips = el('div', 'example-chips');

    const examples = [
      { q: '你会什么', label: '你会什么 ↗' },
      { q: '有哪些项目', label: '有哪些项目 ↗' },
      { q: '介绍一下这个项目。', label: '介绍一下这个项目 ↗' },
      { q: '奶茶项目的自测并发表现如何？', label: '奶茶项目并发自测 ↗' },
      { q: 'RRF 融合常数 k 为什么选 60？', label: 'RRF 常数 k 决策 ↗' },
      { q: '你在生产中用过 LangChain4j 吗？', label: '生产用过 LangChain4j 吗 ↗' }
    ];

    for (const item of examples) {
      const btn = el('button', 'chip-btn', item.label);
      btn.type = 'button';
      btn.dataset.question = item.q;
      btn.addEventListener('click', () => ask(item.q));
      chips.append(btn);
    }

    tray.append(chips);
    body.append(heading, lead, tray);
    welcomeSheet.append(seal, body);
    messagesEl.append(welcomeSheet);

    if (questionInput) {
      questionInput.value = '';
      questionInput.focus();
    }
  }

  // ==========================================================================
  // SECTION 2: 流式传输与 SSE 解析 (Streaming & SSE Parser)
  // ==========================================================================

  /**
   * 提交问题并发起 SSE 流式问答 (POST /api/chat/stream)
   * 按照规范依次消费：route -> trace -> progress -> answer -> done (或 error)
   */
  async function ask(questionText) {
    const q = (questionText || '').trim();
    if (!q || sendBtn.disabled) return;

    // 清除首屏欢迎卡
    const welcomeCard = document.querySelector('#welcome-card');
    if (welcomeCard) welcomeCard.remove();

    // 1. 追加用户提问条
    const userTurn = el('article', 'turn user', q);
    messagesEl.append(userTurn);

    // 2. 准备助手回答外壳
    const turnId = ++turnCounter;
    const assistantTurn = el('article', 'turn assistant');
    assistantTurn.id = `turn-${turnId}`;

    const metaStrip = el('div', 'turn-meta');
    metaStrip.append(el('span', 'meta-pill route-pill', '正在分析意图…'));
    assistantTurn.append(metaStrip);

    // 进度微光状态提示条
    const progressNotice = el('div', 'stream-progress');
    progressNotice.append(el('span', 'pulse-dot'));
    const progressLabel = el('span', 'progress-text', '正在向本地管线发起查阅…');
    progressNotice.append(progressLabel);
    assistantTurn.append(progressNotice);

    // 检索透视容器占位
    const lensContainer = el('div', 'lens-wrapper');
    assistantTurn.append(lensContainer);

    // 回答文本展示区 (全文经引用校验后一次到达淡入，不做假打字机)
    const answerContainer = el('div', 'answer-body');
    assistantTurn.append(answerContainer);

    // 来源卡片与技能卡片容器
    const extraContainer = el('div', 'extra-wrapper');
    assistantTurn.append(extraContainer);

    messagesEl.append(assistantTurn);
    assistantTurn.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    // 禁用发送框
    sendBtn.disabled = true;
    sendBtn.textContent = '查阅中…';
    if (questionInput) questionInput.value = '';

    activeAbortController = new AbortController();

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          question: q,
          history: chatHistory.slice(-10)
        }),
        signal: activeAbortController.signal
      });

      if (!response.ok) {
        let errMessage = '请求未完成，请重试';
        try {
          const errData = await response.json();
          if (errData && errData.error) errMessage = errData.error;
        } catch (_) {}
        throw new Error(errMessage);
      }

      // 消费 ReadableStream
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      let capturedAnswerData = null;

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // 依据 SSE 双换行分包解析
        let boundaryIndex;
        while ((boundaryIndex = buffer.indexOf('\n\n')) !== -1) {
          const rawBlock = buffer.slice(0, boundaryIndex);
          buffer = buffer.slice(boundaryIndex + 2);

          const parsedEvent = parseSseBlock(rawBlock);
          if (!parsedEvent) continue;

          const { event, data } = parsedEvent;

          // -----------------------------------------------------------
          // 事件分支处理：严格遵循规范定义的事件契约
          // -----------------------------------------------------------
          if (event === 'route') {
            renderRouteMeta(metaStrip, data);
          } else if (event === 'trace') {
            renderRetrievalLens(lensContainer, data, turnId);
          } else if (event === 'progress') {
            if (progressLabel && data.message) {
              progressLabel.textContent = data.message;
            }
          } else if (event === 'answer') {
            capturedAnswerData = data;
            // 收到正式回答：移除进度等待条，整体淡入回答文本与来源卡
            if (progressNotice) progressNotice.remove();
            renderAnswerContent(assistantTurn, answerContainer, extraContainer, data, turnId);
          } else if (event === 'error') {
            if (progressNotice) progressNotice.remove();
            assistantTurn.classList.add('error');
            answerContainer.replaceChildren(el('div', 'error-msg', data.error || '请求未完成，请检查输入或稍后重试'));
          } else if (event === 'done') {
            if (progressNotice && progressNotice.parentNode) {
              progressNotice.remove();
            }
          }
        }
      }

      // 成功完成后，将该轮对话存入客户端记忆
      if (capturedAnswerData && capturedAnswerData.answer) {
        chatHistory.push({ role: 'user', text: q });
        chatHistory.push({ role: 'assistant', text: capturedAnswerData.answer });
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        // 用户主动中断
        if (progressNotice && progressNotice.parentNode) progressNotice.remove();
        answerContainer.replaceChildren(el('div', 'abort-msg', '查阅已由用户中断。'));
      } else {
        if (progressNotice && progressNotice.parentNode) progressNotice.remove();
        assistantTurn.classList.add('error');
        answerContainer.replaceChildren(el('div', 'error-msg', err.message || '连接本地服务失败，请检查后端运行状态。'));
      }
    } finally {
      activeAbortController = null;
      sendBtn.disabled = false;
      sendBtn.textContent = '查阅 ↑';
      if (questionInput) questionInput.focus();
      assistantTurn.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }

  /**
   * 解析单段标准 SSE 数据帧
   */
  function parseSseBlock(block) {
    if (!block || !block.trim()) return null;
    const lines = block.split(/\r?\n/);
    let eventName = 'message';
    const dataLines = [];

    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim());
      }
    }

    let parsedData = null;
    const rawData = dataLines.join('\n');
    try {
      parsedData = rawData ? JSON.parse(rawData) : {};
    } catch (_) {
      parsedData = rawData;
    }

    return { event: eventName, data: parsedData };
  }

  // ==========================================================================
  // SECTION 3: 证据桌 DOM 渲染 (Rendering Engine)
  // ==========================================================================

  /**
   * 渲染路由元信息栏 (route 事件)
   */
  function renderRouteMeta(metaStrip, routeData) {
    metaStrip.replaceChildren();

    const intentTag = routeData.intent || 'retrieve';
    const provider = routeData.provider || 'local';
    const explanation = routeData.explanation || '';

    const routePill = el('span', 'meta-pill route-pill', `路由: ${provider} / ${intentTag}`);
    metaStrip.append(routePill);

    if (explanation) {
      metaStrip.append(el('span', 'meta-pill', `[${explanation}]`));
    }
  }

  /**
   * 渲染回答全文、技能卡与引用卡 (answer 事件)
   */
  function renderAnswerContent(turnEl, answerContainer, extraContainer, data, turnId) {
    // 1. 判断是否属于拒答状态
    if (data.mode === 'insufficient-evidence') {
      turnEl.classList.add('refusal');
    }

    // 2. 如果发生了多轮改写消解，在元信息栏补充消解提示
    const metaStrip = turnEl.querySelector('.turn-meta');
    if (metaStrip && data.resolvedQuestion) {
      metaStrip.append(el('span', 'meta-pill meta-resolved', `消解为: ${data.resolvedQuestion}`));
    }
    if (metaStrip && data.mode) {
      metaStrip.append(el('span', 'meta-pill', data.mode));
    }

    // 3. 渲染回答正文，并将 [n] 转换为可交互的 citation chip
    renderAnswerWithChips(answerContainer, data.answer || '', turnId);

    // 4. 渲染结构化技能列表 (若存在)
    if (data.skills && data.skills.length > 0) {
      const skillsDeck = el('div', 'skills-deck');
      for (const sk of data.skills) {
        const slip = el('div', 'skill-slip');
        slip.append(el('div', 'skill-title', sk.name));
        if (sk.level) slip.append(el('p', 'skill-detail', `熟练程度：${sk.level}`));
        if (sk.tasks) slip.append(el('p', 'skill-detail', `能力自述：${sk.tasks}`));
        if (sk.boundaries) slip.append(el('p', 'skill-detail', `范围限制：${sk.boundaries}`));
        if (sk.evidence && sk.evidence.length > 0) {
          slip.append(el('p', 'skill-detail', `佐证材料：${sk.evidence.join('；')}`));
        }
        if (sk.updatedAt) slip.append(el('p', 'skill-detail', `核对日期：${sk.updatedAt}`));
        skillsDeck.append(slip);
      }
      extraContainer.append(skillsDeck);
    }

    // 5. 渲染引用卡片列 (Archival Slips)
    if (data.sources && data.sources.length > 0) {
      renderSourceSlips(extraContainer, data.sources, turnId);
    }
  }

  /**
   * 解析正文中的 [n] 引用标记，构造安全的 DOM 文本与 Chip 按钮
   * 注：完全基于 TextNode 与 Button DOM 创建，杜绝 innerHTML
   */
  function renderAnswerWithChips(container, text, turnId) {
    container.replaceChildren();
    if (!text) return;

    const regex = /\[(\d+)\]/g;
    let lastIndex = 0;
    let match;

    while ((match = regex.exec(text)) !== null) {
      const matchStart = match.index;
      const matchEnd = regex.lastIndex;

      // 文本切片
      if (matchStart > lastIndex) {
        const plainText = text.substring(lastIndex, matchStart);
        container.append(document.createTextNode(plainText));
      }

      // 提取引用序号
      const num = parseInt(match[1], 10);
      const chip = el('button', 'citation-chip', `[${num}]`);
      chip.type = 'button';
      chip.title = `定位引用档案 [${num}]`;
      chip.setAttribute('aria-label', `定位引用档案 ${num}`);

      // 点击跳转至对应卡片并触发高亮闪烁
      chip.addEventListener('click', () => {
        focusSourceSlip(turnId, num);
      });

      container.append(chip);
      lastIndex = matchEnd;
    }

    if (lastIndex < text.length) {
      container.append(document.createTextNode(text.substring(lastIndex)));
    }
  }

  /**
   * 渲染来源卡片列（档案便签样式，带左侧松绿墨线）
   * 注：字符起止偏移由后端 Java String (UTF-16 code units) 计出，
   * 前端 JavaScript string.slice 同样按 UTF-16 处理，两者完全对齐。
   */
  function renderSourceSlips(container, sources, turnId) {
    const drawer = el('section', 'sources-drawer');
    const heading = el('div', 'sources-heading');
    heading.append(el('span', '', `引用来源档案（共 ${sources.length} 条）`));
    drawer.append(heading);

    const list = el('div', 'sources-cards-list');

    for (const src of sources) {
      const slip = el('div', 'source-slip');
      slip.id = `source-${turnId}-${src.number}`;
      slip.dataset.sourceNumber = String(src.number);
      if (src.entryId) slip.dataset.entryId = src.entryId;

      // 头部条签
      const header = el('div', 'slip-header');
      const titleGroup = el('div', 'slip-title-group');
      titleGroup.append(el('span', 'slip-chip', `[${src.number}]`));
      titleGroup.append(el('h4', 'slip-title', src.title || '公开条目'));
      header.append(titleGroup);

      if (src.locator) {
        header.append(el('span', 'slip-locator', src.locator));
      }
      slip.append(header);

      // 来源出处与核验日期
      const meta = el(
        'div',
        'slip-meta',
        `出处：${src.origin || '已批准公开资料'} · 核验日期：${src.updatedAt || '—'} · 条目ID：${src.entryId || '—'}`
      );
      slip.append(meta);

      // 摘录文本内容：命中范围底纹高亮
      const contentBox = el('div', 'slip-content');
      const excerpt = src.excerpt || '';

      // 若后端下发了相对该片段的命中范围或完整摘录，用 mark 标签凸显
      const mark = el('mark', 'evidence-mark');
      mark.textContent = excerpt;
      contentBox.append(mark);

      slip.append(contentBox);
      list.append(slip);
    }

    drawer.append(list);
    container.append(drawer);
  }

  // ==========================================================================
  // SECTION 4: 检索透视与交互联动 (Retrieval Lens & Interactions)
  // ==========================================================================

  /**
   * 渲染检索透视面板 (trace 事件)
   * 呈现三列：BM25 词法路 / 向量语义路 / RRF 融合与准入门判定
   */
  function renderRetrievalLens(container, traceData, turnId) {
    container.replaceChildren();
    if (!traceData) return;

    const hits = traceData.hits || [];
    const embeddingMode = traceData.embeddingMode || '—';
    const lexicalCands = traceData.lexicalCandidates ?? 0;
    const vectorCands = traceData.vectorCandidates ?? 0;

    const details = el('details', 'retrieval-lens');
    details.open = true; // 默认展开透视，让管线成为演示主角

    const summary = el('summary', 'lens-summary');
    summary.append(el('span', '', '◈ 检索透视（BM25 · 向量 · RRF 融合）'));
    summary.append(el('span', 'lens-count-badge', `${hits.length} 条准入入选 · 模式 ${embeddingMode}`));
    details.append(summary);

    const lensBody = el('div', 'lens-body');
    const grid = el('div', 'lens-grid');

    // -------------------------------------------------------------
    // 第 1 列：BM25 词法候选路
    // -------------------------------------------------------------
    const colBm25 = el('div', 'lens-column');
    const bm25Header = el('div', 'col-header');
    bm25Header.append(el('span', '', 'BM25 词法路'));
    bm25Header.append(el('span', 'col-header-sub', `${lexicalCands} 条候选`));
    colBm25.append(bm25Header);

    const lexicalHits = hits.filter(h => h.lexicalRank !== null && h.lexicalRank !== undefined);
    if (lexicalHits.length === 0) {
      colBm25.append(el('div', 'col-empty-hint', '无词法通过条目'));
    } else {
      for (const h of lexicalHits) {
        const item = el('div', 'lens-candidate');
        const itemHeader = el('div', 'candidate-header');
        itemHeader.append(el('span', 'cand-rank', `#${h.lexicalRank}`));
        itemHeader.append(el('span', 'cand-title', h.title || h.entryId));
        item.append(itemHeader);

        const scoreText = h.lexicalScore !== null ? `BM25得分: ${Number(h.lexicalScore).toFixed(3)}` : 'BM25: —';
        item.append(el('span', 'cand-score', `${scoreText} · ${h.locator || ''}`));
        colBm25.append(item);
      }
    }
    grid.append(colBm25);

    // -------------------------------------------------------------
    // 第 2 列：向量语义候选路
    // -------------------------------------------------------------
    const colVector = el('div', 'lens-column');
    const vecHeader = el('div', 'col-header');
    vecHeader.append(el('span', '', '向量语义路'));
    vecHeader.append(el('span', 'col-header-sub', `${vectorCands} 条候选`));
    colVector.append(vecHeader);

    if (embeddingMode === 'lexical-only') {
      colVector.append(el('div', 'col-empty-hint', '纯词法模式（向量通道关闭）'));
    } else {
      const vectorHits = hits.filter(h => h.vectorRank !== null && h.vectorRank !== undefined);
      if (vectorHits.length === 0) {
        colVector.append(el('div', 'col-empty-hint', '无向量通过条目'));
      } else {
        for (const h of vectorHits) {
          const item = el('div', 'lens-candidate');
          const itemHeader = el('div', 'candidate-header');
          itemHeader.append(el('span', 'cand-rank', `#${h.vectorRank}`));
          itemHeader.append(el('span', 'cand-title', h.title || h.entryId));
          item.append(itemHeader);

          const transScore = h.vectorScoreTransformed !== null ? Number(h.vectorScoreTransformed).toFixed(3) : '—';
          const rawScore = h.vectorScoreRaw !== null ? Number(h.vectorScoreRaw).toFixed(3) : '—';
          item.append(el('span', 'cand-score', `变换分: ${transScore} (余弦 ${rawScore})`));
          colVector.append(item);
        }
      }
    }
    grid.append(colVector);

    // -------------------------------------------------------------
    // 第 3 列：RRF 融合与准入门判定
    // -------------------------------------------------------------
    const colRrf = el('div', 'lens-column');
    const rrfHeader = el('div', 'col-header');
    rrfHeader.append(el('span', '', 'RRF 融合与准入'));
    rrfHeader.append(el('span', 'col-header-sub', `最终入选 ${hits.length} 条`));
    colRrf.append(rrfHeader);

    if (hits.length === 0) {
      colRrf.append(el('div', 'col-empty-hint', '未跨过准入门槛，已触发拒答'));
    } else {
      for (let i = 0; i < hits.length; i++) {
        const h = hits[i];
        const item = el('div', 'lens-candidate');
        const itemHeader = el('div', 'candidate-header');
        itemHeader.append(el('span', 'cand-rank', `[${i + 1}]`));
        itemHeader.append(el('span', 'cand-title', h.title || h.entryId));
        item.append(itemHeader);

        const rrfText = h.rrfScore ? `RRF分: ${Number(h.rrfScore).toFixed(4)}` : 'RRF: —';
        item.append(el('span', 'cand-score', rrfText));

        // 准入通道徽标
        const admittedTag = h.admittedBy || 'lexical';
        const admittedLabel =
          admittedTag === 'both' ? '双通道准入 (BM25+向量)' : admittedTag === 'vector' ? '向量通道准入' : '词法通道准入';
        const badge = el('span', `badge-admitted ${admittedTag}`, admittedLabel);
        item.append(badge);

        if (h.gateNote) {
          item.append(el('div', 'cand-gate', h.gateNote));
        }

        colRrf.append(item);
      }
    }
    grid.append(colRrf);

    lensBody.append(grid);
    details.append(lensBody);
    container.append(details);
  }

  /**
   * 引用 Chip 点击联动：平滑滚动至对应的来源卡片并触发琥珀底纹闪烁
   */
  function focusSourceSlip(turnId, sourceNumber) {
    const slipId = `source-${turnId}-${sourceNumber}`;
    const slip = document.getElementById(slipId);
    if (!slip) return;

    // 清理其他可能处于高亮状态的卡片
    document.querySelectorAll('.source-slip.highlight-active').forEach(el => {
      el.classList.remove('highlight-active');
    });

    slip.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    slip.classList.add('highlight-active');

    // 2.5 秒后自动平滑淡出高亮边框
    setTimeout(() => {
      slip.classList.remove('highlight-active');
    }, 2500);
  }

  // ==========================================================================
  // 事件监听绑定 (Event Listeners)
  // ==========================================================================

  // 表单提交
  if (chatForm) {
    chatForm.addEventListener('submit', event => {
      event.preventDefault();
      if (questionInput) ask(questionInput.value);
    });
  }

  // 键盘快捷键：Enter 提交，Shift+Enter 换行
  if (questionInput) {
    questionInput.addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        chatForm.requestSubmit();
      }
    });
  }

  // 全局快捷键：按 '/' 快速聚焦输入框
  window.addEventListener('keydown', event => {
    if (
      event.key === '/' &&
      document.activeElement !== questionInput &&
      document.activeElement.tagName !== 'INPUT' &&
      document.activeElement.tagName !== 'TEXTAREA'
    ) {
      event.preventDefault();
      if (questionInput) {
        questionInput.focus();
        questionInput.select();
      }
    }
  });

  // "新对话" 重置按钮
  if (resetBtn) {
    resetBtn.addEventListener('click', resetConversation);
  }

  // 预设示范问题按钮代理
  document.addEventListener('click', event => {
    const btn = event.target.closest('[data-question]');
    if (btn && btn.dataset.question) {
      ask(btn.dataset.question);
    }
  });

  // 页面就绪后拉取后端实时状态
  loadStatus();
})();
