import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gzip } from 'node:zlib';
import { promisify } from 'node:util';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import nbt from 'prismarine-nbt';
import { adventureMemories } from '../src/saves/memories.js';
import { emptyLibrary, longString, mergeProjection, scanRoot, stableId, uuid } from '../src/saves/reader.js';
import type { Projection, SaveLibrary } from '../src/shared/types.js';

const zip = promisify(gzip);
const maid = (entityUuid: string, ownerUuid: string) => nbt.comp({
  id: nbt.string('touhou_little_maid:maid'), UUID: nbt.intArray(entityUuid.split('').slice(0, 4).map((_, i) => i + 1)),
  Owner: nbt.string(ownerUuid), ModelId: nbt.string('geckolib:winefox'), CustomName: nbt.string('{"text":"酒狐"}'),
  MaidFavorability: nbt.int(8), MaidHistorySummary: nbt.string('一起看过日落'),
  MaidHistoryChat: { type: 'list', value: { type: 'compound', value: [
    nbt.comp({ role: nbt.string('user'), message: nbt.string('你看，天黑了'), game_time: nbt.long(120) }),
    nbt.comp({ role: nbt.string('assistant'), message: nbt.string('嗯，我们回家吧'), game_time: nbt.long(121) }),
  ] } },
  KillRecord: nbt.comp({ KillRecord: nbt.int(12), Slime: nbt.int(2) }), MaidGameSkillData: nbt.comp({ Gomoku: nbt.int(3) }),
});

test('save reader handles legacy ids, deduplicates projections, and keeps world sources', async () => {
  assert.equal(longString([0, 121]), '121'); assert.equal(uuid('ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB'), 'abcdefab-cdef-abcd-efab-cdefabcdefab');
  const root = await mkdtemp(path.join(process.env.TEMP ?? '.', 'winefox-reader-'));
  try {
    const world = path.join(root, 'saves', 'Sunset'); await mkdir(world, { recursive: true });
    const level = nbt.comp({ Data: nbt.comp({ LevelName: nbt.string('Sunset'), DataVersion: nbt.int(3105), Time: nbt.long([0, 450]), Player: nbt.comp({ UUID: nbt.string('abcdefab-cdef-abcd-efab-cdefabcdefab') }) }) });
    await writeFile(path.join(world, 'level.dat'), await zip(nbt.writeUncompressed(level as never)));
    const scanned = await scanRoot({ id: stableId(root), path: root });
    assert.equal(scanned.worlds.length, 1); assert.equal(scanned.worlds[0].name, 'Sunset'); assert.equal(scanned.worlds[0].gameTime, '450');
    assert.deepEqual(scanned.projections, []); assert.deepEqual(scanned.playerUuids, ['abcdefab-cdef-abcd-efab-cdefabcdefab']);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('adventure memories stay source-labelled and projection merges do not duplicate chat', () => {
  const base: SaveLibrary = { ...emptyLibrary(), ownerUuid: 'owner', worlds: [{ id: 'world', rootId: 'root', path: 'x', name: '日落世界', scannedAt: 1, status: 'ready', warnings: [], filesRead: 1 }] };
  const projection: Projection = { id: 'projection', worldId: 'world', entityUuid: 'maid', ownerUuid: 'owner', name: '酒狐', modelId: 'geckolib:winefox', dimension: 'minecraft:overworld', kills: { total: 12 }, chats: [{ id: 'chat', role: 'assistant', content: '回家吧' }], summaries: ['一起看过日落'], sources: ['entities/r.0.0.mca'], sourceKind: 'entity', observedAt: 1, importedAt: 1 };
  base.projections = [projection]; const memories = adventureMemories(base); assert.equal(memories.length, 3); assert.match(memories[0].content, /日落世界/);
  const merged = mergeProjection(projection, { ...projection, sourceKind: 'backup', sources: ['data/maid_backups/x.dat'], importedAt: 2 }); assert.equal(merged.chats.length, 1); assert.equal(merged.sources.length, 2);
});
