import { _electron as electron } from 'playwright';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

const directory = await mkdtemp(path.join(tmpdir(), 'winefox-qa-'));
const packaged = process.argv.includes('--packaged');
const packagedRoot = path.resolve('release/WineFoxPet');
const env = { ...process.env, WINEFOX_DATA_DIR: directory };
delete env.ELECTRON_RUN_AS_NODE;
await mkdir('qa-artifacts', { recursive: true });
let application;
try {
  application = await electron.launch(packaged
    ? { executablePath: path.join(packagedRoot, '酒狐.exe'), args: [], cwd: directory, env }
    : { args: [path.resolve('dist-electron/electron/main.js')], cwd: directory, env });
  assert.equal(await application.evaluate(({ app }) => app.isPackaged), packaged);
  const page = await application.firstWindow();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.waitForFunction(() => window.petQA?.inspect().ready && typeof window.petQA?.inspect().memories === 'number');
  await page.evaluate(async () => {
    const state = await window.petBridge.invoke('snapshot');
    await window.petBridge.invoke('settings', { ...state.settings, motion: false });
  });
  await page.screenshot({ path: 'qa-artifacts/greeting.png' });
  await page.getByRole('button', { name: '记忆本', exact: true }).click();
  await page.getByRole('textbox', { name: '记忆内容' }).fill('今天一起看了日落');
  await page.getByRole('button', { name: '记住', exact: true }).click();
  await page.getByText('今天一起看了日落', { exact: true }).waitFor();
  await page.getByRole('button', { name: '关闭面板' }).click();
  await page.getByRole('button', { name: '聊天', exact: true }).click();
  await page.getByRole('textbox', { name: '消息', exact: true }).fill('你还记得吗');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await page.locator('#panel').getByText('我记着呢：今天一起看了日落。', { exact: true }).waitFor();
  assert.equal(await page.locator('#bubble').isHidden(), false);
  await page.screenshot({ path: 'qa-artifacts/chat.png' });
  await page.getByRole('button', { name: '关闭面板' }).click();
  for (const pose of ['sit', 'sleep', 'wave', 'idle']) {
    await page.evaluate(pose => window.petQA.pose(pose), pose);
    await page.waitForTimeout(150);
    await page.screenshot({ path: `qa-artifacts/${pose}.png` });
  }
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByLabel('播放动画').uncheck();
  await page.getByRole('button', { name: '保存设置' }).click();
  await page.waitForFunction(() => document.querySelector('#panel').hidden);
  const snapshot = await page.evaluate(() => window.petBridge.invoke('snapshot'));
  assert.equal(snapshot.settings.motion, false);
  assert.equal(snapshot.messages.length, 2);
  assert.equal(snapshot.memories.length, 1);
  assert.equal(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isAlwaysOnTop()), true);
  // Exercise hit testing, click-through, and the actual frameless window drag.
  await page.mouse.move(12, 220);
  await page.waitForFunction(() => window.petQA.inspect().interactive === false);
  await page.mouse.move(240, 370);
  await page.waitForFunction(() => window.petQA.inspect().interactive === true);
  const before = await page.evaluate(() => window.petBridge.invoke('window:bounds'));
  await page.mouse.down();
  await page.waitForFunction(() => window.petQA.inspect().dragging === true);
  await page.waitForTimeout(80);
  await page.mouse.move(260, 385);
  await page.waitForTimeout(150);
  await page.mouse.up();
  const after = await page.evaluate(() => window.petBridge.invoke('window:bounds'));
  assert.ok(Math.abs(after.x - before.x) > 5 || Math.abs(after.y - before.y) > 5, 'Dragging should move the window');
  await page.evaluate(() => { window.petQA.pose('idle'); document.body.style.background = '#edf0e9'; });
  await page.waitForTimeout(100);
  await page.screenshot({ path: 'qa-artifacts/ordinary-mode.png' });
  // Validate the compiled service locates editable prompts even outside the project cwd.
  const promptRoot = packaged ? packagedRoot : process.cwd();
  const expectedPrompt = `${(await readFile(path.join(promptRoot, 'personas/winefox.md'), 'utf8')).trim()}\n\n${(await readFile(path.join(promptRoot, 'prompts/desktop.md'), 'utf8')).trim()}`;
  let receivedPrompt;
  const provider = createServer(async (request, response) => {
    let raw = ''; for await (const chunk of request) raw += chunk;
    receivedPrompt = JSON.parse(raw).messages[0].content;
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ choices: [{ message: { content: '人格已加载。' } }] }));
  });
  try {
    await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
    const port = provider.address().port;
    const reply = await page.evaluate(async ({ port, settings }) => {
      await window.petBridge.invoke('settings', { ...settings, provider: 'remote', baseUrl: `http://127.0.0.1:${port}/v1`, model: 'qa' });
      return window.petBridge.invoke('chat', { content: '读取人格' });
    }, { port, settings: snapshot.settings });
    assert.equal(reply.message.content, '人格已加载。');
    assert.equal(receivedPrompt, expectedPrompt);
  } finally { await new Promise(resolve => provider.close(() => resolve())); }
  assert.deepEqual(errors, []);
  if (packaged) await page.screenshot({ path: 'qa-artifacts/packaged.png' });
  console.log(JSON.stringify({ status: 'passed', packaged, compiledPrompts: true, ...await page.evaluate(() => window.petQA.inspect()), screenshots: 'qa-artifacts/' }, null, 2));
} finally {
  if (application) await application.close();
  await rm(directory, { recursive: true, force: true });
}
