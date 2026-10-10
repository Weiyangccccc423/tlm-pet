import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { AgentService } from '../src/agent/service.js';
import type { Reply, Snapshot } from '../src/shared/types.js';

test('new AI chats reread both prompt files; missing or empty files send no request and save no chat', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'winefox-prompts-'));
  let requests = 0;
  let round = 0;
  const prompts: string[] = [];
  const server = createServer(async (request, response) => {
    let raw = ''; for await (const chunk of request) raw += chunk;
    const input = JSON.parse(raw);
    prompts.push(input.messages[0].content);
    requests++;
    assert.equal(input.messages[0].role, 'system');
    const context = JSON.parse(input.messages[1].content.split('：')[1]);
    assert.equal(context.mode, 'normal');
    assert.equal(context.gameSavesConnected, false);
    assert.equal(context.memorySource, 'manual');
    assert.deepEqual(context.memories, ['测试记忆']);
    response.setHeader('Content-Type', 'application/json');
    if (round++ === 0) {
      // Editing during a tool chain must only affect the next conversation.
      await writeFile(path.join(root, 'personas/winefox.md'), '# 新人格\n改为轻快的语气。');
      await writeFile(path.join(root, 'prompts/desktop.md'), '# 新桌面规则\n简短回应。');
      response.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [
        { id: 'pose', type: 'function', function: { name: 'set_pose', arguments: '{"pose":"wave"}' } },
      ] } }] }));
    } else response.end(JSON.stringify({ choices: [{ message: { content: '你好呀。' } }] }));
  });
  try {
    await mkdir(path.join(root, 'personas'));
    await mkdir(path.join(root, 'prompts'));
    await writeFile(path.join(root, 'personas/winefox.md'), '\uFEFF# 原人格\n温柔的酒狐。');
    await writeFile(path.join(root, 'prompts/desktop.md'), '# 原桌面规则\n陪伴日常。');
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const address = server.address() as { port: number };
    const service = new AgentService(path.join(root, 'data'), undefined, root);
    await service.invoke('settings', { provider: 'remote', baseUrl: `http://127.0.0.1:${address.port}/v1`, model: 'test' });
    await service.invoke('remember', { content: '测试记忆' });
    assert.equal((await service.invoke('chat', { content: '你好' }) as Reply).pose, 'wave');
    assert.equal(prompts[0], prompts[1]);
    assert.ok(prompts[0].startsWith('# 原人格'));
    assert.match(prompts[0], /原桌面规则/);
    await service.invoke('chat', { content: '再聊一条' });
    assert.match(prompts[2], /新人格/);
    assert.match(prompts[2], /新桌面规则/);
    assert.doesNotMatch(prompts[2], /原人格|原桌面规则/);

    await writeFile(path.join(root, 'personas/winefox.md'), ' \n');
    await assert.rejects(service.invoke('chat', { content: '不能使用空人设' }), /personas\/winefox.md.*不能为空/);
    await writeFile(path.join(root, 'personas/winefox.md'), '酒狐');
    await rm(path.join(root, 'prompts/desktop.md'));
    await assert.rejects(service.invoke('chat', { content: '不能缺少规则' }), /无法读取提示词文件 prompts\/desktop.md/);
    await writeFile(path.join(root, 'prompts/desktop.md'), 'x'.repeat(24_001));
    await assert.rejects(service.invoke('chat', { content: '不能发送超长规则' }), /24,000/);
    assert.equal(requests, 3);
    assert.equal((await service.invoke('snapshot') as Snapshot).messages.length, 4);
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  }
});
