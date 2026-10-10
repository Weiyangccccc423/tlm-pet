import { readFile } from 'node:fs/promises';
import path from 'node:path';

async function readPrompt(root: string, relativePath: string): Promise<string> {
  let content: string;
  try {
    content = (await readFile(path.join(root, relativePath), 'utf8')).replace(/^\uFEFF/, '').trim();
  } catch {
    throw new Error(`无法读取提示词文件 ${relativePath}，请检查文件是否存在且可读`);
  }
  if (!content) throw new Error(`提示词文件 ${relativePath} 不能为空`);
  if (content.length > 24_000) throw new Error(`提示词文件 ${relativePath} 不能超过 24,000 字符`);
  return content;
}

/** Read once per chat; all tool turns keep the same persona and behavior rules. */
export async function loadSystemPrompt(root: string): Promise<string> {
  const [persona, desktop] = await Promise.all([
    readPrompt(root, 'personas/winefox.md'),
    readPrompt(root, 'prompts/desktop.md'),
  ]);
  return `${persona}\n\n${desktop}`;
}
