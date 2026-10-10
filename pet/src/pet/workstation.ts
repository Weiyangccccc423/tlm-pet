import * as THREE from 'three';
import { GAME_KEYS, type GameInputState, type GameKey, type MouseButton } from '../shared/types';

interface Keycap { group: THREE.Group; material: THREE.MeshLambertMaterial; label: THREE.Mesh; x: number; z: number }
export class Workstation {
  readonly root = new THREE.Group();
  private keys = new Map<GameKey, Keycap>();
  private held = new Set<GameKey>();
  private buttons = new Set<MouseButton>();
  private pulses = new Map<GameKey | MouseButton, number>();
  private mouse = new THREE.Group();
  private mouseButtons = new Map<MouseButton, THREE.Mesh<THREE.BoxGeometry, THREE.MeshLambertMaterial>>();
  private mouseX = 0;
  private mouseZ = 0;
  private wheelUntil = 0;
  private wheel = new THREE.Mesh(new THREE.BoxGeometry(0.35, 0.2, 0.75), new THREE.MeshLambertMaterial({ color: '#d5ac5e' }));
  private keyboard = new THREE.Group();
  private activeKey?: GameKey;
  private hand = new THREE.Vector3(3.2, 23.8, -8.1);
  private handTarget = this.hand.clone();
  private previousFrame = performance.now();
  keyEnergy = 0;
  mouseEnergy = 0;
  constructor() {
    this.root.visible = false;
    const cream = new THREE.MeshLambertMaterial({ color: '#f5eee0' });
    const wood = new THREE.MeshLambertMaterial({ color: '#ba865f' });
    const rose = new THREE.MeshLambertMaterial({ color: '#b84642' });
    const dark = new THREE.MeshLambertMaterial({ color: '#434255' });
    const box = (width: number, height: number, depth: number, x: number, y: number, z: number, material: THREE.Material) => {
      const mesh = new THREE.Mesh(new THREE.BoxGeometry(width, height, depth), material);
      mesh.position.set(x, y, z); this.root.add(mesh); return mesh;
    };
    box(28, 1, 12.5, 0, 20.4, -8.5, cream);
    box(28.2, 0.3, 12.7, 0, 19.9, -8.5, wood);
    for (const x of [-12, 12]) for (const z of [-13.3, -3.7]) box(1.1, 19.5, 1.1, x, 9.75, z, wood);
    // Seat and backrest; desk accessories share the pet's scale and rotation.
    box(11, 1.3, 9, 0, 17, 1.5, dark);
    box(11, 11, 1.4, 0, 22, 6, rose);
    for (const x of [-4, 4]) for (const z of [-1.4, 4.5]) box(0.8, 16.4, 0.8, x, 8.2, z, dark);
    box(15.2, 0.4, 8.8, 3.2, 21.1, -7.8, rose);
    // Rotate the entire layout: the space/modifier row and lettering face Wine Fox.
    this.keyboard.position.set(3.2, 0, -7.8); this.keyboard.rotation.y = Math.PI;
    this.root.add(this.keyboard);
    box(7.1, 0.16, 8.4, -8.5, 21, -8.5, new THREE.MeshLambertMaterial({ color: '#eee1cb' }));
    const layout: [GameKey, number, number, number][] = [
      ['Q', 7, -5.8, 3.8], ['W', 3.2, -5.8, 3.4], ['E', -0.6, -5.8, 3.8],
      ['A', 7, -8.4, 3.8], ['S', 3.2, -8.4, 3.4], ['D', -0.6, -8.4, 3.8],
      ['Shift', 7.1, -11, 3.5], ['Space', 2.9, -11, 4.5], ['Ctrl', -1.1, -11, 3.1],
    ];
    for (const [name, x, z, width] of layout) {
      const group = new THREE.Group(); group.position.set(x - 3.2, 21.7, z + 8.5);
      const material = new THREE.MeshLambertMaterial({ color: name === 'Space' ? '#e6c995' : '#fff5e6' });
      const cap = new THREE.Mesh(new THREE.BoxGeometry(width - 0.25, 0.7, 2.2), material); group.add(cap);
      const canvas = document.createElement('canvas'); canvas.width = 512; canvas.height = 384;
      const context = canvas.getContext('2d')!;
      const text = name === 'Shift' ? '⇧' : name === 'Space' ? '␣' : name === 'Ctrl' ? 'Ctrl' : name;
      context.font = '900 100px "Segoe UI", "Segoe UI Symbol", sans-serif';
      const metrics = context.measureText(text);
      const fontSize = Math.floor(100 * Math.min(464 / metrics.width, 322 / (metrics.actualBoundingBoxAscent + metrics.actualBoundingBoxDescent)));
      context.font = `900 ${fontSize}px "Segoe UI", "Segoe UI Symbol", sans-serif`;
      const fitted = context.measureText(text);
      context.fillStyle = '#301810'; context.textAlign = 'center';
      context.fillText(text, 256, (384 + fitted.actualBoundingBoxAscent - fitted.actualBoundingBoxDescent) / 2);
      const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
      texture.anisotropy = 8;
      const label = new THREE.Mesh(new THREE.PlaneGeometry(width - 0.4, 2), new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false }));
      label.rotation.x = -Math.PI / 2; label.rotation.z = Math.PI; label.position.y = 0.37;
      group.add(label); this.keyboard.add(group);
      this.keys.set(name, { group, material, label, x: 6.4 - x, z: -16.3 - z });
    }
    const mouseBody = new THREE.Mesh(new THREE.BoxGeometry(3.2, 0.9, 4.5), dark);
    this.mouse.add(mouseBody); this.mouse.position.set(-8.5, 21.7, -10.5); this.root.add(this.mouse);
    for (const [name, x] of [['left', 0.78], ['right', -0.78]] as const) {
      const button = new THREE.Mesh(new THREE.BoxGeometry(1.35, 0.18, 2), new THREE.MeshLambertMaterial({ color: '#d6a3a0' }));
      button.position.set(x, 0.53, -0.9); this.mouse.add(button); this.mouseButtons.set(name, button);
    }
    this.wheel.position.set(0, 0.58, -0.85); this.mouse.add(this.wheel);
    // A small mug helps the desk feel like a companion's own corner.
    const mug = new THREE.Mesh(new THREE.CylinderGeometry(1.05, 0.88, 2.3, 12), cream);
    mug.position.set(11.5, 22.1, -11.5); this.root.add(mug);
    const drink = new THREE.Mesh(new THREE.CylinderGeometry(0.87, 0.87, 0.05, 12), new THREE.MeshLambertMaterial({ color: '#744d39' }));
    drink.position.set(11.5, 23.25, -11.5); this.root.add(drink);
  }
  updateInput(input: GameInputState): void {
    const now = performance.now();
    this.held = new Set(input.keys); this.buttons = new Set(input.buttons);
    for (const key of [...input.taps, ...input.clicks]) this.pulses.set(key, now + 150);
    if (input.taps.length) this.activeKey = input.taps.at(-1);
    this.mouseX = THREE.MathUtils.clamp(this.mouseX - input.dx * 0.012, -1.2, 1.2);
    this.mouseZ = THREE.MathUtils.clamp(this.mouseZ - input.dy * 0.012, -1.1, 1.1);
    if (input.wheel) this.wheelUntil = now + 170;
    if (input.status === 'off' || input.status === 'error') { this.held.clear(); this.buttons.clear(); this.pulses.clear(); this.mouseX = this.mouseZ = 0; this.activeKey = undefined; }
  }
  animate(time: number, motion: boolean): { hand: THREE.Vector3; mouse: number; mouseX: number; mouseZ: number } {
    const now = performance.now();
    const delta = Math.min((now - this.previousFrame) / 1000, 0.1); this.previousFrame = now;
    for (const [key, cap] of this.keys) {
      const pressed = this.held.has(key) || (this.pulses.get(key) ?? 0) > now;
      cap.group.position.y = pressed ? 21.4 : 21.7;
      cap.material.color.set(pressed ? '#d89057' : key === 'Space' ? '#e6c995' : '#fff5e6');
    }
    for (const [key, button] of this.mouseButtons) {
      const pressed = this.buttons.has(key) || (this.pulses.get(key) ?? 0) > now;
      button.position.y = pressed ? 0.38 : 0.53; button.material.color.set(pressed ? '#bf5a50' : '#d6a3a0');
    }
    this.keyEnergy += (([...this.held].length || GAME_KEYS.some(key => (this.pulses.get(key) ?? 0) > now) ? 1 : 0) - this.keyEnergy) * 0.35;
    const pressed = (key: GameKey) => this.held.has(key) || (this.pulses.get(key) ?? 0) > now;
    if (!this.activeKey || !pressed(this.activeKey)) this.activeKey = [...this.held].at(-1) ?? GAME_KEYS.find(key => pressed(key));
    const cap = this.activeKey ? this.keys.get(this.activeKey)! : this.keys.get('S')!;
    // Lift a little while travelling, then settle the palm above the pressed key.
    const travel = Math.hypot(this.hand.x - cap.x, this.hand.z - cap.z);
    const tapping = motion ? this.keyEnergy * (0.5 + Math.sin(time * 22) * 0.5) * 0.28 : 0;
    this.handTarget.set(cap.x, 23.65 + Math.min(travel * 0.13, 0.55) - tapping, cap.z);
    if (motion) this.hand.lerp(this.handTarget, 1 - Math.exp(-delta * 20));
    else this.hand.copy(this.handTarget);
    this.mouseEnergy += ((this.buttons.size || (this.pulses.get('left') ?? 0) > now || (this.pulses.get('right') ?? 0) > now ? 1 : 0) - this.mouseEnergy) * 0.35;
    this.mouse.position.set(-8.5 + this.mouseX, 21.7, -10.5 + this.mouseZ);
    const wheelPressed = this.buttons.has('middle') || (this.pulses.get('middle') ?? 0) > now;
    this.wheel.position.y = wheelPressed ? 0.4 : 0.58;
    this.wheel.material.color.set(wheelPressed || now < this.wheelUntil ? '#fff0af' : '#d5ac5e');
    return { hand: this.hand, mouse: this.mouseEnergy, mouseX: this.mouseX, mouseZ: this.mouseZ };
  }
  inspect(): Record<string, unknown> {
    return { keys: [...this.held], buttons: [...this.buttons], mouseOffset: [this.mouseX, this.mouseZ], keyEnergy: this.keyEnergy, wheelActive: performance.now() < this.wheelUntil,
      handKey: this.activeKey ?? null, handTarget: this.handTarget.toArray(), handPosition: this.hand.toArray(),
      keyboardFacing: 'pet', keyPositions: Object.fromEntries([...this.keys].map(([key, cap]) => [key, [cap.x, cap.z]])) };
  }
}
