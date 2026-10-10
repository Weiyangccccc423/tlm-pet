import type { Memory, SaveLibrary } from '../shared/types.js';
import { stableId } from './reader.js';

export function adventureMemories(library: SaveLibrary): Memory[] {
  if (!library.ownerUuid) return [];
  const result: Memory[] = [];
  for (const projection of library.projections.filter(p => p.ownerUuid === library.ownerUuid).sort((a, b) => a.importedAt - b.importedAt)) {
    const world = library.worlds.find(w => w.id === projection.worldId);
    if (!world) continue;
    const source = `世界「${world.name}」的投影「${projection.name}」`;
    const facts: string[] = [];
    if (projection.favorability !== undefined) facts.push(`好感度 ${projection.favorability}`);
    if (projection.kills.total !== undefined) facts.push(`累计击杀 ${projection.kills.total}`);
    if (projection.kills.dragon !== undefined) facts.push(`末影龙击杀 ${projection.kills.dragon}`);
    if (projection.kills.wither !== undefined) facts.push(`凋灵击杀 ${projection.kills.wither}`);
    if (projection.gomokuWins !== undefined) facts.push(`五子棋获胜 ${projection.gomokuWins}`);
    if (facts.length) result.push({ id: stableId(projection.id, 'facts'), content: `${source}的已保存状态：${facts.join('；')}。这些是累计数字，不能还原每次事件的时间或过程。`, createdAt: projection.importedAt });
    for (const summary of projection.summaries.slice(-3)) result.push({ id: stableId(projection.id, summary), content: `${source}的游戏内对话摘要：${summary.slice(0, 3000)}`, createdAt: projection.importedAt });
    for (const chat of projection.chats.slice(-24)) result.push({ id: chat.id, content: `${source}的已保存对话${chat.gameTime ? `（游戏刻 ${chat.gameTime}）` : ''}：${chat.role === 'user' ? '玩家' : '酒狐'}说「${chat.content.slice(0, 2000)}」`, createdAt: projection.importedAt });
  }
  return result.slice(-80);
}
