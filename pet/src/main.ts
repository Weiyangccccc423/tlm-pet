import './styles.css';
import { PetScene } from './pet/scene';
import { GAME_KEYS, type GameInputState, type GameKey, type Pose, type Reply, type SaveLibrary, type Snapshot } from './shared/types';

const desktop = !!window.petBridge?.desktop;
document.body.classList.toggle('desktop', desktop);
const paths: Record<string, string> = {
  chat: '<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-3 3v-11a8.5 8.5 0 0 1 17-1Z"/>',
  heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/>',
  book: '<path d="M4 3h13a3 3 0 0 1 3 3v15H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Zm0 14h16M8 7h8M8 11h5"/>',
  settings: '<path d="m9 3-.7 2.2-2 .9-2.1-.5-2 3.4 1.5 1.7v2.5L2.2 15l2 3.4 2.1-.5 2 .9L9 21h4l.7-2.2 2-.9 2.1.5 2-3.4-1.5-1.8v-2.4l1.5-1.8-2-3.4-2.1.5-2-.9L13 3Z"/><circle cx="11" cy="12" r="3"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  send: '<path d="m22 2-7 20-4-9-9-4 20-7ZM11 13 22 2"/>',
  hide: '<path d="M5 12h14"/>',
};
const icon = (name: string) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] ?? paths.heart}</svg>`;
const app = document.querySelector<HTMLDivElement>('#app')!;
app.innerHTML = `
  <aside class="preview-intro"><div class="eyebrow">WINE FOX · DESKTOP COMPANION</div><h1>把日常，<br>过成一起的时光。</h1><p>一只住在桌面上的酒狐。<br>陪你闲聊、发呆，也记住小小的日常。</p><div class="intro-note"><span class="dot"></span> 普通模式 · 第一版</div><small>拖动酒狐来移动窗口，点击她打个招呼。<br>在「记忆本」里，写下想一起记住的事。</small><a href="https://github.com/TartaricAcid/WineFoxModel" target="_blank" rel="noreferrer">模型：WineFoxModel · CC BY-NC-SA 4.0</a></aside>
  <main class="pet-shell">
    <div class="mode-tag"><div class="mode-switch" aria-label="陪伴模式"><button data-action="mode" data-mode="normal" aria-label="普通模式">普通</button><button data-action="mode" data-mode="game" aria-label="游戏模式">游戏</button></div><span class="provider-label">离线陪伴</span></div>
    <button id="input-status" class="input-status" data-action="input-sync" hidden aria-label="暂停或开启键鼠同步">键鼠同步</button>
    <div id="bubble" class="bubble" role="status"><span id="bubble-text">等我一下，我马上就来。</span><button data-action="dismiss-bubble" aria-label="收起气泡">${icon('close')}</button></div>
    <div id="pet-stage" aria-label="酒狐，点击打招呼，拖动移动" role="img"></div>
    <div class="pet-shadow"></div>
    <section id="panel" class="panel" hidden aria-label="陪伴面板"></section>
    <div class="pet-name"><span>酒狐</span><small id="pose-label">陪着你</small></div>
    <nav class="toolbar" aria-label="酒狐互动">
      <button data-action="greet" title="打个招呼" aria-label="打个招呼">${icon('heart')}</button>
      <button data-action="rest" title="坐下 / 休息" aria-label="坐下或休息">${icon('moon')}</button>
      <span class="divider"></span>
      <button data-action="chat" title="聊天" aria-label="聊天">${icon('chat')}</button>
      <button data-action="memories" title="记忆本" aria-label="记忆本">${icon('book')}</button>
      <button data-action="settings" title="设置" aria-label="设置">${icon('settings')}</button>
      ${desktop ? `<span class="divider"></span><button data-action="hide" title="隐藏到托盘" aria-label="隐藏到托盘">${icon('hide')}</button>` : ''}
    </nav>
    <div id="error" class="error-toast" role="alert" hidden></div>
  </main>`;

const element = <T extends HTMLElement = HTMLElement>(selector: string) => app.querySelector<T>(selector)!;
const panel = element('#panel');
const bubble = element('#bubble');
const scene = new PetScene(element('#pet-stage'));
let state: Snapshot;
let currentPanel = '';
let currentPose: Pose = 'idle';
let pending = false;
let poseTimer: ReturnType<typeof setTimeout>;
let bubbleTimer: ReturnType<typeof setTimeout>;
let errorTimer: ReturnType<typeof setTimeout>;
let lastPaused: boolean | undefined;
let inputState: GameInputState = { status: 'off', keys: [], buttons: [], taps: [], clicks: [], dx: 0, dy: 0, wheel: 0 };
function receiveInput(input: GameInputState): void { inputState = input; scene.input(input); renderInputStatus(); }
window.petBridge?.onInput?.(receiveInput);
function renderInputStatus(): void {
  const status = element('#input-status');
  status.hidden = state?.settings.mode !== 'game' || !!currentPanel;
  status.dataset.status = inputState.status;
  status.textContent = !state?.settings.inputSync ? '键鼠同步已关闭' : currentPanel || currentPose !== 'idle' ? '键鼠同步已暂停' :
    inputState.status === 'active' ? '● 键鼠同步中' : inputState.status === 'error' ? '同步未能启动 · 点击重试' : inputState.status === 'preview' ? '● 网页内同步' : '键鼠同步准备中';
}
function updateInputPause(): void {
  const paused = !!currentPanel || currentPose !== 'idle' || dragging;
  renderInputStatus();
  if (desktop && paused !== lastPaused) { lastPaused = paused; void invoke<GameInputState>('input:pause', paused).then(receiveInput).catch(showError); }
  if (!desktop && state) receiveInput({ ...inputState, status: state.settings.mode === 'game' && state.settings.inputSync && !paused ? 'preview' : 'off', keys: [], buttons: [], taps: [], clicks: [], dx: 0, dy: 0, wheel: 0 });
}

async function invoke<T>(action: string, payload?: unknown): Promise<T> {
  if (window.petBridge) return window.petBridge.invoke<T>(action, payload);
  const response = await fetch('/api/pet', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, payload }) });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || '请求失败');
  return result as T;
}
function showError(error: unknown): void {
  const toast = element('#error');
  toast.textContent = error instanceof Error ? error.message.replace(/^Error invoking remote method '[^']+': Error: /, '') : '操作失败，请重试';
  toast.hidden = false;
  clearTimeout(errorTimer); errorTimer = setTimeout(() => { toast.hidden = true; }, 8000);
}
function say(text: string, keep = false): void {
  element('#bubble-text').textContent = text;
  bubble.hidden = false;
  clearTimeout(bubbleTimer);
  if (!keep) bubbleTimer = setTimeout(() => { bubble.hidden = true; }, 12_000);
}
function pose(value: Pose): void {
  clearTimeout(poseTimer);
  currentPose = value; scene.setPose(value);
  element('#pose-label').textContent = value === 'idle' && state?.settings.mode === 'game' ? '陪你一起玩' : { idle: '陪着你', wave: '见到你真好', sit: '坐一会儿', sleep: '休息中' }[value];
  updateInputPause();
  if (value === 'wave') poseTimer = setTimeout(() => pose('idle'), 3200);
}
function applyState(): void {
  scene.scale = state.settings.scale; scene.motion = state.settings.motion;
  element('.provider-label').textContent = state.settings.provider === 'local' ? '离线陪伴' : 'AI 已配置';
  scene.setMode(state.settings.mode);
  document.body.classList.toggle('game-mode', state.settings.mode === 'game');
  for (const button of app.querySelectorAll<HTMLElement>('.mode-switch button')) button.classList.toggle('active', button.dataset.mode === state.settings.mode);
  if (currentPose === 'idle') element('#pose-label').textContent = state.settings.mode === 'game' ? '陪你一起玩' : '陪着你';
  updateInputPause();
}
function header(title: string, subtitle: string): string {
  return `<header class="panel-header"><div><h2>${title}</h2><p>${subtitle}</p></div><button data-action="close-panel" class="icon-button" aria-label="关闭面板">${icon('close')}</button></header>`;
}
function closePanel(): void {
  currentPanel = ''; panel.hidden = true; panel.classList.remove('chat-panel'); document.body.classList.remove('chat-open');
  for (const button of app.querySelectorAll('.toolbar button')) button.classList.remove('active');
  updateInputPause();
}
function renderPanel(kind: string): void {
  if (!state) return;
  currentPanel = kind; panel.hidden = false; panel.classList.toggle('chat-panel', kind === 'chat'); document.body.classList.toggle('chat-open', kind === 'chat'); bubble.hidden = true;
  updateInputPause();
  for (const button of app.querySelectorAll<HTMLElement>('.toolbar button')) button.classList.toggle('active', button.dataset.action === kind);
  if (kind === 'chat') {
    panel.innerHTML = header('和酒狐聊聊', state.settings.provider === 'local' ? '离线陪伴 · 可在设置中接入 AI' : 'AI 陪伴 · 记忆与动作已连接')
      + `<div class="messages" aria-live="polite"></div><form id="chat-form" class="chat-form"><input name="content" aria-label="消息" placeholder="今天过得怎么样？" maxlength="4000" autocomplete="off" required><button class="send-button" aria-label="发送" ${pending ? 'disabled' : ''}>${icon('send')}</button></form>`;
    renderMessages();
    element<HTMLInputElement>('#chat-form input').focus();
  } else if (kind === 'memories') {
    panel.innerHTML = header('我们的记忆本', '手动记忆和各世界酒狐的冒险记录。')
      + '<div class="memory-tabs"><button type="button" data-action="memory-tab" data-tab="manual">手动记忆</button><button type="button" data-action="memory-tab" data-tab="worlds">世界存档</button></div><div id="memory-view"></div>';
    renderMemoryView('manual');
    renderMemories();
  } else if (kind === 'settings') {
    panel.innerHTML = header('陪伴设置', '让酒狐用你喜欢的方式陪着你。') + `<form id="settings-form" class="settings-form">
      <label>聊天方式<select name="provider"><option value="local">离线陪伴</option><option value="remote">AI 服务</option></select></label>
      <div id="remote-fields"><label>服务地址<input name="baseUrl" type="url" placeholder="https://服务地址/v1"></label><label>模型名称<input name="model" placeholder="你的服务提供的模型名称"></label><label>API 密钥<input name="apiKey" type="password" autocomplete="off" placeholder="${state.settings.hasApiKey ? '已保存；留空保持现有密钥' : '可选，仅在你的服务需要时填写'}"></label><label class="check"><input name="clearApiKey" type="checkbox">清除已保存的密钥</label></div>
      <p id="provider-note" class="footnote"></p>
      <div class="settings-row"><label class="check"><input name="alwaysOnTop" type="checkbox">总在最前</label><label class="check"><input name="motion" type="checkbox">播放动画</label></div>
      <label class="check"><input name="inputSync" type="checkbox">游戏模式同步键盘和鼠标</label>
      <p class="footnote">${desktop ? '跟随 Q / W / E / A / S / D、空格、Shift、Ctrl 和鼠标。打开面板、休息或隐藏时暂停。同步状态只用于本机动画。' : '网页预览只跟随本页面的键鼠，桌面版支持全局同步。'}</p>
      <label>酒狐大小 <input name="scale" type="range" min="0.7" max="1.4" step="0.05"></label>
      <button class="primary-button">保存设置</button><p class="footnote">${desktop ? '数据保存在本机。密钥由系统加密存储。' : '网页为预览模式。密钥仅在预览服务运行期间保留。'}</p>
      <details class="credits"><summary>角色素材与署名</summary><p>酒狐素材：TartaricAcid/WineFoxModel<br>CC BY-NC-SA 4.0 · 非商业使用<br>模型原作：完美冻结；动画及修改：星屑海螺、哥斯拉／映素作坊团队等。完整协作者名单见随包 assets/CREDITS.md。桌面注视、招手与休息动作由本原型适配。</p><button type="button" data-action="source">查看作者原始声明</button></details>
      </form>`;
    const form = element<HTMLFormElement>('#settings-form');
    for (const [key, value] of Object.entries(state.settings)) {
      const field = form.elements.namedItem(key) as HTMLInputElement | HTMLSelectElement | null;
      if (!field) continue;
      if (field instanceof HTMLInputElement && field.type === 'checkbox') field.checked = Boolean(value);
      else field.value = String(value);
    }
    if (!desktop) (form.elements.namedItem('alwaysOnTop') as HTMLInputElement).disabled = true;
    providerFields();
  }
}
function renderMemoryView(tab: 'manual' | 'worlds'): void {
  const view = element('#memory-view');
  for (const button of app.querySelectorAll<HTMLElement>('[data-action=memory-tab]')) button.classList.toggle('active', button.dataset.tab === tab);
  if (tab === 'manual') {
    view.innerHTML = '<div class="memory-list"></div><form id="memory-form" class="memory-form"><input name="content" aria-label="记忆内容" placeholder="比如：我们第一次一起看了日落" maxlength="2000" required><button class="primary-button">记住</button></form><p class="footnote">这里保存你手动记下的日常。</p>';
    renderMemories(); return;
  }
  const saves = state.saves;
  const roots = saves.roots.map(root => `<article class="save-root"><strong>${escapeHtml(root.path)}</strong><small>${root.error ? `读取失败：${escapeHtml(root.error)}` : root.scannedAt ? `上次读取：${new Date(root.scannedAt).toLocaleString('zh-CN')}` : '未读取'}</small><button type="button" data-action="remove-save" data-id="${root.id}">移除</button></article>`).join('');
  const ownerOptions = saves.playerUuids.map(uuid => `<option value="${uuid}" ${uuid === saves.ownerUuid ? 'selected' : ''}>${uuid}</option>`).join('');
  const worlds = saves.worlds.map(world => {
    const projections = saves.projections.filter(projection => projection.worldId === world.id);
    return `<article class="save-world"><header><strong>${escapeHtml(world.name)}</strong><small>${world.status === 'ready' ? '已读取' : world.status === 'partial' ? '部分读取' : world.status === 'missing' ? '暂时找不到' : '读取失败'} · ${world.filesRead} 个文件</small></header><p>${projections.length ? projections.map(projection => `${escapeHtml(projection.name)}：${projection.chats.length} 条对话${projection.favorability === undefined ? '' : ` · 好感度 ${projection.favorability}`}`).join('<br>') : '没有找到可识别的酒狐投影。'}</p>${world.warnings.length ? `<small class="save-warning">${escapeHtml(world.warnings[0])}</small>` : ''}</article>`;
  }).join('');
  view.innerHTML = `<div class="save-actions"><button type="button" class="primary-button" data-action="choose-save">选择存档目录</button><button type="button" class="secondary-button" data-action="refresh-saves">刷新</button></div>${roots || '<p class="footnote">还没有添加目录。可以选择 Minecraft 实例目录、saves 目录或单个世界。</p>'}<label class="save-owner">玩家 UUID<select data-action="save-owner"><option value="">自动选择（仅一个玩家时）</option>${ownerOptions}</select></label><div class="save-worlds">${worlds || '<div class="empty-memory"><strong>还没有世界记录</strong><p>添加目录后，桌宠会只读扫描世界存档。</p></div>'}</div><p class="footnote">只读取 level.dat、实体区域、玩家数据和酒狐备份；不会写回存档。游戏运行中可能有未保存数据，建议退出世界后刷新。</p>`;
}
function escapeHtml(value: string): string { return value.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]!)); }
function providerFields(): void {
  const remote = element<HTMLSelectElement>('[name=provider]').value === 'remote';
  element('#remote-fields').hidden = !remote;
  element('#provider-note').textContent = remote ? '兼容 Chat Completions 与工具调用的服务。聊天会发送至你填写的服务地址。' : '无需联网。使用简单的预设回应，可以打招呼、休息和回忆；开放聊天需要接入 AI。';
}
function appendMessage(role: 'user' | 'assistant', content: string): void {
  const list = app.querySelector('.messages'); if (!list) return;
  const item = document.createElement('div'); item.className = `message ${role}`;
  const author = document.createElement('small'); author.textContent = role === 'user' ? '你' : '酒狐';
  const text = document.createElement('p'); text.textContent = content;
  item.append(author, text); list.append(item); list.scrollTop = list.scrollHeight;
}
function renderMessages(): void {
  element('.messages').replaceChildren();
  if (!state.messages.length) appendMessage('assistant', '我在这里。想聊什么，就告诉我吧。');
  else for (const message of state.messages) appendMessage(message.role, message.content);
  if (pending) appendMessage('assistant', '正在想怎么回答你…');
}
function renderMemories(): void {
  const list = element('.memory-list'); list.replaceChildren();
  if (!state.memories.length) {
    const empty = document.createElement('div'); empty.className = 'empty-memory';
    empty.innerHTML = `${icon('book')}<strong>故事从这里开始</strong><p>还没有写下记忆。<br>先收藏一件今天的小事吧。</p>`; list.append(empty);
  }
  for (const memory of [...state.memories].reverse()) {
    const item = document.createElement('article'); item.className = 'memory-item';
    const content = document.createElement('p'); content.textContent = memory.content;
    const date = document.createElement('small'); date.textContent = new Date(memory.createdAt).toLocaleDateString('zh-CN');
    const remove = document.createElement('button'); remove.textContent = '忘记'; remove.dataset.action = 'forget'; remove.dataset.id = memory.id;
    item.append(content, date, remove); list.append(item);
  }
}
app.addEventListener('change', event => {
  const target = event.target as HTMLElement;
  if (target.matches('[name=provider]')) providerFields();
  if (target.matches('[data-action=save-owner]')) void invoke<Snapshot>('saves:owner', { uuid: (target as HTMLSelectElement).value }).then(result => { state = result; renderMemoryView('worlds'); }).catch(showError);
});
app.addEventListener('click', event => {
  const button = (event.target as HTMLElement).closest<HTMLElement>('[data-action]'); if (!button) return;
  const action = button.dataset.action;
  if (action === 'greet') { pose('wave'); say('你来啦！今天也一起慢慢来吧。'); }
  else if (action === 'mode' && state) {
    void invoke<Snapshot>('mode', { mode: button.dataset.mode }).then(result => {
      state = result; applyState(); pose('idle'); closePanel();
      say(state.settings.mode === 'game' ? '我也准备好键盘和鼠标啦，一起玩吧！' : '电脑先放一边，继续陪你。');
    }).catch(showError);
  } else if (action === 'input-sync' && state) {
    const enabled = inputState.status === 'error' ? true : !state.settings.inputSync;
    void invoke<Snapshot>('input-sync', { enabled }).then(result => { state = result; applyState(); }).catch(showError);
  }
  else if (action === 'rest') {
    const next = currentPose === 'sit' ? 'sleep' : currentPose === 'sleep' ? 'idle' : 'sit';
    pose(next); say({ sit: '坐一会儿吧，我就在旁边。', sleep: '我先眯一会儿，有事叫我。', idle: '休息好啦，继续陪你。' }[next]);
  } else if (['chat', 'memories', 'settings'].includes(action ?? '')) { if (currentPanel === action) closePanel(); else renderPanel(action!); }
  else if (action === 'memory-tab') renderMemoryView(button.dataset.tab === 'worlds' ? 'worlds' : 'manual');
  else if (action === 'choose-save') void invoke<Snapshot>('saves:choose').then(result => { if (result) { state = result; renderMemoryView('worlds'); say('我会把这些世界里的记录带回来。'); } }).catch(showError);
  else if (action === 'refresh-saves') void invoke<Snapshot>('saves:refresh').then(result => { state = result; renderMemoryView('worlds'); }).catch(showError);
  else if (action === 'remove-save') void invoke<Snapshot>('saves:remove', { id: button.dataset.id }).then(result => { state = result; renderMemoryView('worlds'); }).catch(showError);
  else if (action === 'close-panel') closePanel();
  else if (action === 'dismiss-bubble') bubble.hidden = true;
  else if (action === 'hide') void invoke('window:hide').catch(showError);
  else if (action === 'source') { if (desktop) void invoke('window:source').catch(showError); else window.open('https://github.com/TartaricAcid/WineFoxModel#readme', '_blank', 'noopener,noreferrer'); }
  else if (action === 'forget') void invoke<Snapshot>('forget', { id: button.dataset.id }).then(result => { state = result; if (currentPanel === 'memories') renderMemories(); }).catch(showError);
});
app.addEventListener('submit', event => {
  event.preventDefault();
  const form = event.target as HTMLFormElement;
  const values = new FormData(form);
  if (form.id === 'chat-form') {
    if (pending) return;
    const content = String(values.get('content') ?? '').trim(); if (!content) return;
    pending = true; appendMessage('user', content); appendMessage('assistant', '正在想怎么回答你…'); form.reset();
    form.querySelector('button')!.disabled = true;
    void invoke<Reply>('chat', { content }).then(reply => {
      state.messages.push({ id: crypto.randomUUID(), role: 'user', content, createdAt: Date.now() }, reply.message);
      state.memories = reply.memories;
      if (reply.pose) pose(reply.pose);
      if (currentPanel !== 'chat') say(reply.message.content);
    }).catch(error => { showError(error); }).finally(() => {
      pending = false;
      if (currentPanel === 'chat') { renderMessages(); element<HTMLButtonElement>('#chat-form button').disabled = false; }
    });
  } else if (form.id === 'memory-form') {
    void invoke<Snapshot>('remember', { content: values.get('content') }).then(result => { state = result; if (currentPanel === 'memories') { renderMemories(); form.reset(); } }).catch(showError);
  } else if (form.id === 'settings-form') {
    const button = form.querySelector('button')!; button.disabled = true;
    void invoke<Snapshot>('settings', {
      provider: values.get('provider'), baseUrl: values.get('baseUrl'), model: values.get('model'), apiKey: values.get('apiKey'),
      clearApiKey: values.has('clearApiKey'), alwaysOnTop: desktop ? values.has('alwaysOnTop') : state.settings.alwaysOnTop,
      motion: values.has('motion'), scale: Number(values.get('scale')), inputSync: values.has('inputSync'),
    }).then(result => { state = result; applyState(); closePanel(); say('好啦，已经按你的喜好记住了。'); }).catch(showError).finally(() => { button.disabled = false; });
  }
});

// Only the visible character and controls intercept clicks on the transparent desktop.
let interactive = true;
let dragging = false;
let dragStart: { sx: number; sy: number; x: number; y: number; moved: boolean; pointer: number } | undefined;
let moveBusy = false;
let moveTarget: { x: number; y: number } | undefined;
async function moveWindow(): Promise<void> {
  if (moveBusy) return; moveBusy = true;
  try { while (moveTarget) { const point = moveTarget; moveTarget = undefined; await invoke('window:move', point); } }
  catch (error) { showError(error); }
  finally { moveBusy = false; }
}
window.addEventListener('pointermove', event => {
  scene.look(event.clientX, event.clientY);
  if (dragStart) {
    const dx = event.screenX - dragStart.sx, dy = event.screenY - dragStart.sy;
    if (Math.abs(dx) + Math.abs(dy) > 5) dragStart.moved = true;
    if (desktop && dragStart.moved) { moveTarget = { x: dragStart.x + dx, y: dragStart.y + dy }; void moveWindow(); }
  }
  if (!desktop) return;
  const target = event.target as HTMLElement;
  const next = dragging || !!target.closest('button, input, select, .panel, .bubble, .toolbar') || scene.hitTest(event.clientX, event.clientY);
  if (next !== interactive) { interactive = next; void invoke('window:interactive', next).catch(showError); }
});
element('#pet-stage').addEventListener('pointerdown', async event => {
  if (event.button !== 0 || !scene.hitTest(event.clientX, event.clientY)) return;
  dragging = true; element('#pet-stage').setPointerCapture(event.pointerId);
  updateInputPause();
  try {
    const bounds = desktop ? await invoke<{ x: number; y: number }>('window:bounds') : { x: 0, y: 0 };
    if (!dragging) return;
    dragStart = { sx: event.screenX, sy: event.screenY, x: bounds.x, y: bounds.y, moved: false, pointer: event.pointerId };
  } catch (error) { dragging = false; updateInputPause(); showError(error); }
});
function endDrag(event: PointerEvent): void {
  if (!dragging) return;
  const moved = dragStart?.moved;
  dragging = false; dragStart = undefined;
  updateInputPause();
  if (element('#pet-stage').hasPointerCapture(event.pointerId)) element('#pet-stage').releasePointerCapture(event.pointerId);
  if (!moved) { pose('wave'); say('嘿嘿，摸摸头。今天也请多关照。'); }
}
window.addEventListener('pointerup', endDrag);
window.addEventListener('pointercancel', () => { dragging = false; dragStart = undefined; updateInputPause(); });
window.addEventListener('keydown', event => { if (event.key === 'Escape') closePanel(); });
window.petQA = { pose, inspect: () => ({ ...scene.inspect(), desktop, inputStatus: inputState.status, interactive, dragging, pending, memories: state?.memories.length, messages: state?.messages.length }) };
void Promise.all([scene.load(), invoke<Snapshot>('snapshot')]).then(([, snapshot]) => {
  state = snapshot; applyState();
  if (desktop) void invoke<GameInputState>('input:state').then(receiveInput).catch(showError);
  say(state.settings.mode === 'game' ? '键盘和鼠标都准备好啦，陪你一起玩。' : '我来啦。今天，也一起度过吧。', true);
}).catch(error => { showError(error); say('加载遇到了问题，请检查下方提示。', true); });

if (!desktop) {
  const held = new Set<GameKey>();
  const buttons = new Set<'left' | 'right' | 'middle'>();
  const active = () => state?.settings.mode === 'game' && state.settings.inputSync && !currentPanel && currentPose === 'idle';
  const send = (patch: Partial<GameInputState>) => receiveInput({ status: 'preview', keys: [...held], buttons: [...buttons], taps: [], clicks: [], dx: 0, dy: 0, wheel: 0, ...patch });
  const keyName = (event: KeyboardEvent): GameKey | undefined => {
    const name = event.code === 'Space' ? 'Space' : event.code.startsWith('Shift') ? 'Shift' : event.code.startsWith('Control') ? 'Ctrl' : event.code.replace('Key', '');
    return GAME_KEYS.includes(name as GameKey) ? name as GameKey : undefined;
  };
  window.addEventListener('keydown', event => { const key = keyName(event); if (!active() || !key) return; held.add(key); send({ taps: event.repeat ? [] : [key] }); });
  window.addEventListener('keyup', event => { const key = keyName(event); if (!key) return; held.delete(key); if (active()) send({}); });
  window.addEventListener('mousemove', event => { if (active()) send({ dx: event.movementX, dy: event.movementY }); });
  window.addEventListener('mousedown', event => { if (!active()) return; const button = (['left', 'middle', 'right'] as const)[event.button]; if (button) { buttons.add(button); send({ clicks: [button] }); } });
  window.addEventListener('mouseup', event => { const button = (['left', 'middle', 'right'] as const)[event.button]; if (button) buttons.delete(button); if (active()) send({}); });
  window.addEventListener('wheel', event => { if (active()) send({ wheel: event.deltaY }); }, { passive: true });
  window.addEventListener('blur', () => { held.clear(); buttons.clear(); receiveInput({ ...inputState, keys: [], buttons: [], taps: [], clicks: [], dx: 0, dy: 0, wheel: 0 }); });
}
