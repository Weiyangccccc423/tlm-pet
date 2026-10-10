export type Pose = 'idle' | 'sit' | 'wave' | 'sleep';
export type PetMode = 'normal' | 'game';
export const GAME_KEYS = ['Q', 'W', 'E', 'A', 'S', 'D', 'Shift', 'Space', 'Ctrl'] as const;
export type GameKey = typeof GAME_KEYS[number];
export type MouseButton = 'left' | 'right' | 'middle';
export interface GameInputState {
  status: 'off' | 'active' | 'error' | 'preview';
  keys: GameKey[];
  buttons: MouseButton[];
  taps: GameKey[];
  clicks: MouseButton[];
  dx: number;
  dy: number;
  wheel: number;
}
export type Role = 'user' | 'assistant';
export interface Message { id: string; role: Role; content: string; createdAt: number }
export interface Memory { id: string; content: string; createdAt: number }
export interface SaveRoot { id: string; path: string; error?: string; scannedAt?: number }
export interface SaveWorld { id: string; rootId: string; path: string; name: string; dataVersion?: number; gameTime?: string; scannedAt: number; status: 'ready' | 'partial' | 'error' | 'missing'; warnings: string[]; filesRead: number }
export interface AdventureChat { id: string; role: 'user' | 'assistant'; content: string; gameTime?: string }
export interface Projection {
  id: string; worldId: string; entityUuid: string; ownerUuid: string; ownerName?: string;
  name: string; modelId: string; dimension: string; position?: number[];
  favorability?: number; kills: { total?: number; slime?: number; wither?: number; dragon?: number }; gomokuWins?: number;
  chats: AdventureChat[]; summaries: string[]; sources: string[]; sourceKind: 'entity' | 'item' | 'backup';
  observedAt: number; importedAt: number;
}
export interface SaveLibrary { roots: SaveRoot[]; worlds: SaveWorld[]; projections: Projection[]; ownerUuid: string; playerUuids: string[] }
export interface Settings {
  provider: 'local' | 'remote';
  baseUrl: string;
  model: string;
  hasApiKey: boolean;
  alwaysOnTop: boolean;
  scale: number;
  motion: boolean;
  mode: PetMode;
  inputSync: boolean;
}
export interface Snapshot { settings: Settings; messages: Message[]; memories: Memory[]; saves: SaveLibrary }
export interface Reply { message: Message; pose?: Pose; memories: Memory[] }
export interface PetBridge {
  invoke<T>(action: string, payload?: unknown): Promise<T>;
  desktop: boolean;
  onInput?: (listener: (state: GameInputState) => void) => () => void;
}
declare global {
  interface Window {
    petBridge?: PetBridge;
    petQA?: { pose: (pose: Pose) => void; inspect: () => Record<string, unknown> };
  }
}
