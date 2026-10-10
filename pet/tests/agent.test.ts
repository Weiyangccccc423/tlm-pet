import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { AgentService } from '../src/agent/service.js';
import type { Snapshot, Reply } from '../src/shared/types.js';

test('memory and conversation survive restart; duplicates and deleted memories stay correct', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'winefox-test-'));
  try {
    const service = new AgentService(directory);
    await service.invoke('remember', { content: '今天一起看了日落' });
    await service.invoke('remember', { content: '今天一起看了日落' });
    const reply = await service.invoke('chat', { content: '你还记得吗' }) as Reply;
    assert.match(reply.message.content, /今天一起看了日落/);
    const restarted = new AgentService(directory);
    let snapshot = await restarted.invoke('snapshot') as Snapshot;
    assert.equal(snapshot.memories.length, 1);
    assert.equal(snapshot.messages.length, 2);
    await restarted.invoke('forget', { id: snapshot.memories[0].id });
    snapshot = await new AgentService(directory).invoke('snapshot') as Snapshot;
    assert.equal(snapshot.memories.length, 0);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('legacy profiles gain mode defaults; mode and sync changes preserve memories and survive restart', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'winefox-modes-'));
  try {
    const initial = new AgentService(directory);
    await initial.invoke('remember', { content: '原来的记忆' });
    await initial.invoke('chat', { content: '你好' });
    const filename = path.join(directory, 'state.json');
    const legacy = JSON.parse(await readFile(filename, 'utf8'));
    delete legacy.settings.mode; delete legacy.settings.inputSync;
    await writeFile(filename, JSON.stringify(legacy));
    const service = new AgentService(directory);
    let state = await service.invoke('snapshot') as Snapshot;
    assert.equal(state.settings.mode, 'normal'); assert.equal(state.settings.inputSync, true);
    await service.invoke('mode', { mode: 'game' });
    await service.invoke('input-sync', { enabled: false });
    await service.invoke('settings', { provider: 'local', scale: 1.2 });
    state = await new AgentService(directory).invoke('snapshot') as Snapshot;
    assert.equal(state.settings.mode, 'game'); assert.equal(state.settings.inputSync, false);
    assert.equal(state.settings.scale, 1.2);
    assert.deepEqual(state.memories, legacy.memories); assert.deepEqual(state.messages, legacy.messages);
    await assert.rejects(service.invoke('mode', { mode: 'unknown' }), /未知陪伴模式/);
    await assert.rejects(service.invoke('input-sync', { enabled: 'yes' }), /是否同步/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('remote agent executes memory and pose tools, stores no plain key, and preserves history on failure', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'winefox-tools-'));
  let requests = 0;
  let fail = false;
  let syncEnabled = true;
  const server = createServer(async (request, response) => {
    let raw = ''; for await (const chunk of request) raw += chunk;
    const input = JSON.parse(raw);
    assert.equal(request.url, '/v1/chat/completions');
    assert.equal(request.headers.authorization, 'Bearer test-secret');
    assert.match(input.messages[0].content, /金色长发/);
    assert.match(input.messages[0].content, /番茄布丁/);
    assert.match(input.messages[0].content, /数据投影分身/);
    const context = JSON.parse(input.messages[1].content.split('：')[1]);
    assert.equal(context.mode, 'game');
    assert.equal(context.gameInputSyncEnabled, syncEnabled);
    assert.ok(!('keys' in context) && !('gameInput' in context));
    response.setHeader('Content-Type', 'application/json');
    if (fail) { response.statusCode = 503; response.end('{}'); return; }
    if (requests++ === 0) response.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: null, tool_calls: [
      { id: 'recall', type: 'function', function: { name: 'recall_memory', arguments: '{"query":"日落"}' } },
      { id: 'pose', type: 'function', function: { name: 'set_pose', arguments: '{"pose":"sit"}' } },
    ] } }] }));
    else {
      const outputs = input.messages.filter((message: { role: string }) => message.role === 'tool');
      assert.equal(outputs.length, 2);
      assert.match(outputs[0].content, /日落/);
      assert.deepEqual(JSON.parse(outputs[1].content), { pose: 'sit' });
      response.end(JSON.stringify({ choices: [{ message: { content: '当然记得那天的日落。坐下慢慢聊吧。' } }] }));
    }
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as { port: number };
  const vault = { encrypt: (value: string) => Buffer.from(value).toString('base64'), decrypt: (value: string) => Buffer.from(value, 'base64').toString() };
  try {
    const service = new AgentService(directory, vault);
    await service.invoke('remember', { content: '一起看日落' });
    const snapshot = await service.invoke('settings', { provider: 'remote', baseUrl: `http://127.0.0.1:${address.port}/v1`, model: 'test', apiKey: 'test-secret' }) as Snapshot;
    assert.equal(snapshot.settings.hasApiKey, true);
    assert.ok(!JSON.stringify(snapshot).includes('test-secret'));
    assert.ok(!(await readFile(path.join(directory, 'state.json'), 'utf8')).includes('test-secret'));
    await service.invoke('mode', { mode: 'game' });
    const restarted = new AgentService(directory, vault);
    const reply = await restarted.invoke('chat', { content: '记得日落吗？' }) as Reply;
    assert.equal(reply.pose, 'sit');
    assert.equal(requests, 2);
    fail = true;
    syncEnabled = false;
    await restarted.invoke('input-sync', { enabled: false });
    await assert.rejects(restarted.invoke('chat', { content: '这条失败的消息' }), /503/);
    assert.equal((await restarted.invoke('snapshot') as Snapshot).messages.length, 2);
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); await rm(directory, { recursive: true, force: true }); }
});
