import { createHash } from 'node:crypto';
import { open, readFile, readdir, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { gunzip, inflate } from 'node:zlib';
import { promisify } from 'node:util';
import nbt from 'prismarine-nbt';
import type { AdventureChat, Projection, SaveRoot, SaveWorld } from '../shared/types.js';

type Compound = Record<string, unknown>;
const MAX_NBT = 32 * 1024 * 1024;
const unzip = promisify(gunzip), unzlib = promisify(inflate);
const models = new Set(['winefox', 'winefox_blue', 'winefox_nine_tailed', 'winefox_hanfu', 'winefox_new_year', 'winefox_jk',
  'winefox_astronaut', 'winefox_kongfu', 'winefox_magical', 'winefox_saint', 'winefox_elf', 'winefox_salesperson',
  'winefox_little', 'winefox_matured', 'winefox_momo', 'winefox_wedding', 'winefox_tactics', 'winefox_mini', 'winefox_survivor'].map(id => `geckolib:${id}`));
// The included TLM custom pack registers its Wine Fox skin as touhou_little_maid_seihou:vivit.
const isWineFoxModel = (value: string) => models.has(value) || /(?:winefox|vivit)/i.test(value);
export const stableId = (...parts: string[]) => createHash('sha256').update(JSON.stringify(parts)).digest('hex');
export const emptyLibrary = () => ({ roots: [], worlds: [], projections: [], ownerUuid: '', playerUuids: [] });
const object = (value: unknown): Compound => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Compound : {};
const text = (value: unknown, limit = 12000) => typeof value === 'string' ? value.slice(0, limit) : '';
const count = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : undefined;
export function longString(value: unknown): string | undefined {
  if (typeof value === 'number' && Number.isSafeInteger(value) || typeof value === 'bigint') return String(value);
  if (Array.isArray(value) && value.length === 2 && value.every(n => typeof n === 'number')) {
    return ((BigInt(value[0]) << 32n) | BigInt(value[1] >>> 0)).toString();
  }
  return undefined;
}
export function uuid(value: unknown): string {
  if (typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) return value.toLowerCase();
  if (Array.isArray(value) && value.length === 4 && value.every(n => Number.isInteger(n))) {
    const hex = value.map(n => (n >>> 0).toString(16).padStart(8, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return '';
}
export async function canonicalRoot(input: unknown): Promise<SaveRoot> {
  if (typeof input !== 'string' || !path.isAbsolute(input)) throw new Error('请选择绝对路径的存档目录');
  const folder = await realpath(input);
  if (!(await stat(folder)).isDirectory()) throw new Error('存档路径必须是文件夹');
  return { id: stableId(process.platform === 'win32' ? folder.toLowerCase() : folder), path: folder };
}
function parse(buffer: Buffer): Compound {
  if (buffer.length > MAX_NBT) throw new Error('NBT 超过 32 MB，已跳过');
  return object(nbt.simplify(nbt.parseUncompressed(buffer, 'big')));
}
async function dat(filename: string): Promise<Compound> {
  const info = await stat(filename);
  if (info.size > MAX_NBT) throw new Error('数据文件超过 32 MB，已跳过');
  const buffer = await readFile(filename);
  return parse(buffer[0] === 0x1f && buffer[1] === 0x8b ? await unzip(buffer, { maxOutputLength: MAX_NBT }) : buffer);
}
function component(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  try {
    const flatten = (value: unknown): string => typeof value === 'string' ? value : Array.isArray(value) ? value.map(flatten).join('') : text(object(value).text, 200) + flatten(object(value).extra ?? []);
    return flatten(JSON.parse(raw)).slice(0, 200);
  } catch { return raw.slice(0, 200); }
}

export interface ScanResult { worlds: SaveWorld[]; projections: Projection[]; playerUuids: string[]; rootError?: string }
export async function scanRoot(root: SaveRoot): Promise<ScanResult> {
  const result: ScanResult = { worlds: [], projections: [], playerUuids: [] };
  try {
    const worldPaths: string[] = [];
    const isWorld = async (folder: string) => { try { return (await stat(path.join(folder, 'level.dat'))).isFile(); } catch { return false; } };
    if (await isWorld(root.path)) worldPaths.push(root.path);
    else {
      let folder = root.path;
      try { if ((await stat(path.join(folder, 'saves'))).isDirectory()) folder = path.join(folder, 'saves'); } catch { /* A saves directory can be selected directly. */ }
      for (const entry of await readdir(folder, { withFileTypes: true })) {
        if (entry.isDirectory() && !entry.isSymbolicLink() && await isWorld(path.join(folder, entry.name))) worldPaths.push(path.join(folder, entry.name));
      }
    }
    for (const folder of worldPaths.sort()) {
      const scanned = await scanWorld(root.id, await realpath(folder));
      result.worlds.push(scanned.world); result.projections.push(...scanned.projections); result.playerUuids.push(...scanned.players);
    }
    if (!worldPaths.length) result.rootError = '没有找到 level.dat；请选择 saves、游戏实例目录或单个世界';
  } catch (error) { result.rootError = `目录无法读取：${error instanceof Error ? error.message : String(error)}`; }
  result.playerUuids = [...new Set(result.playerUuids)];
  return result;
}

async function scanWorld(rootId: string, folder: string): Promise<{ world: SaveWorld; projections: Projection[]; players: string[] }> {
  const world: SaveWorld = { id: stableId(process.platform === 'win32' ? folder.toLowerCase() : folder), rootId, path: folder,
    name: path.basename(folder), scannedAt: Date.now(), status: 'ready', warnings: [], filesRead: 0 };
  const projections = new Map<string, Projection>();
  const players = new Set<string>();
  const warn = (message: string) => { world.status = world.status === 'error' ? 'error' : 'partial'; if (world.warnings.length < 50) world.warnings.push(message); };
  const sourceLabel = (filename: string) => path.relative(folder, filename).replaceAll('\\', '/');
  const collect = (data: unknown, filename: string, dimension: string, kind: Projection['sourceKind'], observedAt: number) => {
    const stack: { value: unknown; nested: boolean; depth: number }[] = [{ value: data, nested: false, depth: 0 }];
    while (stack.length) {
      const { value, nested, depth } = stack.pop()!;
      if (depth > 64) { warn(`${sourceLabel(filename)}：嵌套超过 64 层`); continue; }
      if (Array.isArray(value)) { for (const item of value) if (item && typeof item === 'object') stack.push({ value: item, nested, depth: depth + 1 }); continue; }
      const tag = object(value);
      if (tag.id === 'touhou_little_maid:maid' && isWineFoxModel(text(tag.ModelId))) {
        const ownerUuid = uuid(tag.Owner), entityUuid = uuid(tag.UUID);
        if (ownerUuid && entityUuid) {
          const id = stableId(world.id, ownerUuid, entityUuid);
          const sourceKind = kind === 'backup' ? kind : nested ? 'item' : kind;
          const kills = object(tag.KillRecord);
          const history = Array.isArray(tag.MaidHistoryChat) ? tag.MaidHistoryChat : [];
          const chats: AdventureChat[] = [];
          const occurrences = new Map<string, number>();
          // CappedQueue writes newest first; retain the original game tick, never invent wall time.
          for (const entry of [...history].reverse()) {
            const item = object(entry), content = text(item.message), role = item.role;
            if ((role !== 'user' && role !== 'assistant') || !content.trim()) continue;
            const gameTime = longString(item.game_time);
            const fingerprint = stableId(String(role), content, gameTime ?? '');
            const occurrence = (occurrences.get(fingerprint) ?? 0) + 1; occurrences.set(fingerprint, occurrence);
            chats.push({ id: stableId(id, fingerprint, String(occurrence)), role, content, gameTime });
          }
          const position = Array.isArray(tag.Pos) && tag.Pos.length === 3 && tag.Pos.every(n => typeof n === 'number' && Number.isFinite(n)) ? tag.Pos as number[] : undefined;
          const candidate: Projection = { id, worldId: world.id, ownerUuid, entityUuid, modelId: text(tag.ModelId, 200), name: component(tag.CustomName) || '酒狐',
            ownerName: text(object(tag.MaidAIChat).OwnerName, 200) || undefined, dimension, position,
            favorability: count(tag.MaidFavorability), kills: { total: count(kills.TotalCount) ?? count(kills.KillRecord), slime: count(kills.Slime), wither: count(kills.Wither), dragon: count(kills.EnderDragon) },
            gomokuWins: count(object(tag.MaidGameSkillData).Gomoku), chats, summaries: text(tag.MaidHistorySummary).trim() ? [text(tag.MaidHistorySummary)] : [],
            sources: [sourceLabel(filename)], sourceKind, observedAt, importedAt: world.scannedAt };
          const previous = projections.get(id);
          projections.set(id, previous ? mergeProjection(previous, candidate, true) : candidate);
        } else if (ownerUuid) warn(`${sourceLabel(filename)}：酒狐缺少实体 UUID，无法稳定归档`);
      }
      for (const [key, child] of Object.entries(tag)) {
        if (child && typeof child === 'object') stack.push({ value: child, nested: nested || key === 'MaidInfo' || key === 'MaidData', depth: depth + 1 });
      }
    }
  };
  const readDat = async (filename: string, dimension: string, kind: Projection['sourceKind']) => {
    try { const info = await stat(filename); collect(await dat(filename), filename, dimension, kind, info.mtimeMs); world.filesRead++; }
    catch (error) { warn(`${sourceLabel(filename)}：${error instanceof Error ? error.message : String(error)}`); }
  };
  try {
    const level = object((await dat(path.join(folder, 'level.dat'))).Data);
    world.name = text(level.LevelName, 200) || world.name; world.dataVersion = count(level.DataVersion); world.gameTime = longString(level.Time);
    const player = object(level.Player); const playerUuid = uuid(player.UUID); if (playerUuid) players.add(playerUuid);
    collect(player, path.join(folder, 'level.dat'), text(player.Dimension, 200) || 'minecraft:overworld', 'item', (await stat(path.join(folder, 'level.dat'))).mtimeMs);
    world.filesRead++;
  } catch (error) { world.status = 'error'; warn(`level.dat：${error instanceof Error ? error.message : String(error)}`); return { world, projections: [], players: [] }; }
  const entries = async (directory: string) => {
    try { return await readdir(directory, { withFileTypes: true }); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') warn(`${sourceLabel(directory)}：目录读取失败`); return []; }
  };
  for (const file of await entries(path.join(folder, 'playerdata'))) {
    if (file.isFile() && file.name.endsWith('.dat')) {
      const playerUuid = uuid(file.name.slice(0, -4)); if (playerUuid) players.add(playerUuid);
      await readDat(path.join(folder, 'playerdata', file.name), 'player-inventory', 'item');
    }
  }
  const walkBackups = async (directory: string, depth = 0) => {
    if (depth > 4) return;
    for (const entry of await entries(directory)) {
      if (entry.isDirectory() && !entry.isSymbolicLink()) await walkBackups(path.join(directory, entry.name), depth + 1);
      else if (entry.isFile() && entry.name.endsWith('.dat') && entry.name !== 'index.dat') await readDat(path.join(directory, entry.name), 'backup', 'backup');
    }
  };
  await walkBackups(path.join(folder, 'data', 'maid_backups'));
  const regions = async (dimensionFolder: string, dimension: string) => {
    for (const type of ['entities', 'region']) for (const file of await entries(path.join(dimensionFolder, type))) {
      if (!file.isFile() || !/^r\.-?\d+\.-?\d+\.mca$/.test(file.name)) continue;
      const filename = path.join(dimensionFolder, type, file.name);
      try {
        const info = await stat(filename);
        await readRegion(filename, (data, chunk) => collect(data, `${filename}#${chunk}`, dimension, 'entity', info.mtimeMs), warn);
        const after = await stat(filename);
        if (after.size !== info.size || after.mtimeMs !== info.mtimeMs) warn(`${sourceLabel(filename)}：读取期间存档发生变化，请退出世界后刷新`);
        world.filesRead++;
      } catch (error) { warn(`${sourceLabel(filename)}：${error instanceof Error ? error.message : String(error)}`); }
    }
  };
  await regions(folder, 'minecraft:overworld'); await regions(path.join(folder, 'DIM-1'), 'minecraft:the_nether'); await regions(path.join(folder, 'DIM1'), 'minecraft:the_end');
  const walkDimensions = async (directory: string, depth = 0) => {
    if (depth > 12) { warn('自定义维度目录超过 12 层'); return; }
    for (const entry of await entries(directory)) {
      if (!entry.isDirectory() || entry.isSymbolicLink() || ['entities', 'region', 'poi', 'data'].includes(entry.name)) continue;
      const next = path.join(directory, entry.name);
      const relative = path.relative(path.join(folder, 'dimensions'), next).split(path.sep);
      if (relative.length >= 2) await regions(next, `${relative[0]}:${relative.slice(1).join('/')}`);
      await walkDimensions(next, depth + 1);
    }
  };
  await walkDimensions(path.join(folder, 'dimensions'));
  return { world, projections: [...projections.values()], players: [...players] };
}

/** Read Anvil sector locations rather than loading an entire world region into memory. */
async function readRegion(filename: string, consume: (data: Compound, chunk: number) => void, warn: (message: string) => void): Promise<void> {
  const handle = await open(filename, 'r');
  try {
    const info = await handle.stat();
    const header = Buffer.alloc(8192); const read = await handle.read(header, 0, header.length, 0);
    if (read.bytesRead !== 8192) throw new Error('区域文件头不完整');
    const [, rx, rz] = path.basename(filename).match(/^r\.(-?\d+)\.(-?\d+)\.mca$/)!;
    for (let index = 0; index < 1024; index++) {
      const location = header.readUInt32BE(index * 4), sector = location >>> 8, sectors = location & 255;
      if (!location) continue;
      try {
        if (sector < 2 || !sectors || (sector + sectors) * 4096 > info.size) throw new Error('区域偏移越界');
        const prefix = Buffer.alloc(5); if ((await handle.read(prefix, 0, 5, sector * 4096)).bytesRead !== 5) throw new Error('区块头不完整');
        const length = prefix.readUInt32BE(0), compression = prefix[4] & 127, external = (prefix[4] & 128) !== 0;
        if (length < 1 || length > sectors * 4096 - 4) throw new Error('区块长度无效');
        let buffer: Buffer;
        if (external) {
          const externalFile = path.join(path.dirname(filename), `c.${Number(rx) * 32 + index % 32}.${Number(rz) * 32 + Math.floor(index / 32)}.mcc`);
          if ((await stat(externalFile)).size > MAX_NBT) throw new Error('外部区块过大');
          buffer = await readFile(externalFile);
        } else {
          buffer = Buffer.alloc(length - 1);
          if ((await handle.read(buffer, 0, buffer.length, sector * 4096 + 5)).bytesRead !== buffer.length) throw new Error('区块数据不完整');
        }
        if (compression === 1) buffer = await unzip(buffer, { maxOutputLength: MAX_NBT });
        else if (compression === 2) buffer = await unzlib(buffer, { maxOutputLength: MAX_NBT });
        else if (compression !== 3) throw new Error(`尚不支持压缩类型 ${compression}`);
        consume(parse(buffer), index);
      } catch (error) { warn(`${path.basename(filename)}#${index}：${error instanceof Error ? error.message : String(error)}`); }
    }
  } finally { await handle.close(); }
}

export function mergeProjection(previous: Projection, incoming: Projection, sameScan = false): Projection {
  const priority = { entity: 3, item: 2, backup: 1 };
  const useIncoming = !sameScan || priority[incoming.sourceKind] > priority[previous.sourceKind] || priority[incoming.sourceKind] === priority[previous.sourceKind] && incoming.observedAt >= previous.observedAt;
  const current = useIncoming ? incoming : previous;
  return { ...current, importedAt: incoming.importedAt,
    chats: [...new Map([...previous.chats, ...incoming.chats].map(chat => [chat.id, chat])).values()],
    summaries: [...new Set([...previous.summaries, ...incoming.summaries])], sources: [...new Set([...previous.sources, ...incoming.sources])] };
}
