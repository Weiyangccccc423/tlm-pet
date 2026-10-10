import type { GameInputState, GameKey, MouseButton } from '../src/shared/types.js';
import type { UiohookKeyboardEvent, UiohookMouseEvent } from 'uiohook-napi';

/** Local, transient animation input. Character codes and text are never forwarded or saved. */
export class GameInput {
  private hook?: typeof import('uiohook-napi').uIOhook;
  private codes = new Map<number, GameKey>();
  private pressedCodes = new Set<number>();
  private buttons = new Set<MouseButton>();
  private taps = new Set<GameKey>();
  private clicks = new Set<MouseButton>();
  private cursor?: { x: number; y: number };
  private dx = 0;
  private dy = 0;
  private wheel = 0;
  private running = false;
  private desired = false;
  private loading?: Promise<void>;
  private timer?: ReturnType<typeof setTimeout>;
  private status: GameInputState['status'] = 'off';
  constructor(private send: (state: GameInputState) => void) {}

  snapshot(): GameInputState {
    return { status: this.status, keys: [...new Set([...this.pressedCodes].map(code => this.codes.get(code)!))],
      buttons: [...this.buttons], taps: [...this.taps], clicks: [...this.clicks], dx: this.dx, dy: this.dy, wheel: this.wheel };
  }
  async setEnabled(enabled: boolean): Promise<void> {
    this.desired = enabled;
    if (!enabled) { this.stop(); return; }
    try {
      if (!this.hook) {
        this.loading ??= this.load();
        await this.loading;
      }
      if (!this.desired || this.running) return;
      this.hook!.start(); this.running = true; this.status = 'active'; this.flush();
    } catch {
      this.loading = undefined; this.running = false; this.status = this.desired ? 'error' : 'off'; this.flush();
    }
  }
  private async load(): Promise<void> {
    const { uIOhook, UiohookKey: key } = await import('uiohook-napi');
    this.hook = uIOhook;
    this.codes = new Map([[key.Q, 'Q'], [key.W, 'W'], [key.E, 'E'], [key.A, 'A'], [key.S, 'S'], [key.D, 'D'],
      [key.Space, 'Space'], [key.Shift, 'Shift'], [key.ShiftRight, 'Shift'], [key.Ctrl, 'Ctrl'], [key.CtrlRight, 'Ctrl']]);
    uIOhook.on('keydown', event => this.key(event, true));
    uIOhook.on('keyup', event => this.key(event, false));
    uIOhook.on('mousedown', event => this.mouse(event, true));
    uIOhook.on('mouseup', event => this.mouse(event, false));
    uIOhook.on('mousemove', event => {
      if (!this.running) return;
      if (this.cursor) { this.dx += event.x - this.cursor.x; this.dy += event.y - this.cursor.y; }
      this.cursor = { x: event.x, y: event.y }; this.schedule();
    });
    uIOhook.on('wheel', event => { if (this.running) { this.wheel += event.rotation; this.schedule(); } });
  }
  private key(event: UiohookKeyboardEvent, down: boolean): void {
    if (!this.running) return;
    const key = this.codes.get(event.keycode); if (!key) return;
    if (down) {
      if (!this.pressedCodes.has(event.keycode)) this.taps.add(key);
      this.pressedCodes.add(event.keycode);
    } else this.pressedCodes.delete(event.keycode);
    this.schedule();
  }
  private mouse(event: UiohookMouseEvent, down: boolean): void {
    if (!this.running) return;
    const button = event.button === 1 ? 'left' : event.button === 2 ? 'right' : event.button === 3 ? 'middle' : undefined;
    if (!button) return;
    if (down) { this.buttons.add(button); this.clicks.add(button); } else this.buttons.delete(button);
    this.schedule();
  }
  private schedule(): void { this.timer ??= setTimeout(() => this.flush(), 16); }
  private flush(): void {
    clearTimeout(this.timer); this.timer = undefined;
    this.send(this.snapshot());
    this.taps.clear(); this.clicks.clear(); this.dx = this.dy = this.wheel = 0;
  }
  private stop(): void {
    if (this.running) this.hook?.stop();
    this.running = false; this.status = 'off';
    this.pressedCodes.clear(); this.buttons.clear(); this.taps.clear(); this.clicks.clear(); this.cursor = undefined;
    this.dx = this.dy = this.wheel = 0; this.flush();
  }
  dispose(): void { this.desired = false; this.stop(); }
}
