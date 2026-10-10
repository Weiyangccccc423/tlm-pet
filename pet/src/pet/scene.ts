import * as THREE from 'three';
import { BedrockModel, type AnimationDocument, type GeometryDocument } from './bedrock';
import type { GameInputState, PetMode, Pose } from '../shared/types';
import { Workstation } from './workstation';
import { reachArm } from './arm';

export class PetScene {
  private renderer: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera = new THREE.PerspectiveCamera(30, 1, 0.1, 500);
  private model?: BedrockModel;
  private animations?: AnimationDocument;
  private elapsed = 0;
  private previous = performance.now();
  private pose: Pose = 'idle';
  private poseStart = 0;
  private headX = 0;
  private headY = 0;
  private raycaster = new THREE.Raycaster();
  private bounds = new THREE.Box3();
  private workstation = new Workstation();
  private mode: PetMode = 'normal';
  motion = true;
  scale = 1;
  ready = false;
  constructor(private host: HTMLElement) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    this.renderer.setClearColor(0, 0);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    host.append(this.renderer.domElement);
    this.scene.add(new THREE.AmbientLight(0xffffff, 1.3));
    const key = new THREE.DirectionalLight(0xfff2dc, 0.8); key.position.set(-25, 45, -35); this.scene.add(key);
    const fill = new THREE.DirectionalLight(0xe4eeff, 0.35); fill.position.set(25, 15, 20); this.scene.add(fill);
    this.camera.position.set(-8, 30, -132); this.camera.lookAt(0, 28, 0);
    new ResizeObserver(() => this.resize()).observe(host);
    this.resize();
    this.renderer.setAnimationLoop(() => this.animate());
  }

  async load(): Promise<void> {
    const [geometryResponse, animationResponse, texture] = await Promise.all([
      fetch('./assets/winefox.geo.json'), fetch('./assets/winefox.animation.json'),
      new THREE.TextureLoader().loadAsync('./assets/winefox.png'),
    ]);
    if (!geometryResponse.ok || !animationResponse.ok) throw new Error('酒狐资源加载失败');
    const geometry = await geometryResponse.json() as GeometryDocument;
    this.animations = await animationResponse.json() as AnimationDocument;
    this.model = new BedrockModel(geometry, texture);
    this.model.root.rotation.y = 0.12;
    this.scene.add(this.model.root);
    this.model.root.add(this.workstation.root);
    this.ready = true;
  }

  setPose(pose: Pose): void { this.pose = pose; this.poseStart = this.elapsed; }
  setMode(mode: PetMode): void {
    this.mode = mode;
    if (mode === 'game') { this.camera.position.set(-22, 66, -113); this.camera.lookAt(0, 24, -1); }
    else { this.camera.position.set(-8, 30, -132); this.camera.lookAt(0, 28, 0); }
  }
  input(state: GameInputState): void { this.workstation.updateInput(state); }
  inspect(): Record<string, unknown> {
    const palm = this.model?.bones.get('RightHand');
    return { ready: this.ready, pose: this.pose, mode: this.mode, gameInput: this.workstation.inspect(),
      keyboardHand: palm ? this.model!.root.worldToLocal(palm.getWorldPosition(new THREE.Vector3())).toArray() : null,
      bones: this.model?.bones.size ?? 0, frames: this.renderer.info.render.frame, triangles: this.renderer.info.render.triangles };
  }
  hitTest(clientX: number, clientY: number): boolean {
    if (!this.model) return false;
    const rect = this.host.getBoundingClientRect();
    this.raycaster.setFromCamera(new THREE.Vector2((clientX - rect.left) / rect.width * 2 - 1, -(clientY - rect.top) / rect.height * 2 + 1), this.camera);
    return this.raycaster.intersectObject(this.model.root, true).some(hit => {
      let node: THREE.Object3D | null = hit.object;
      while (node) { if (!node.visible) return false; node = node.parent; }
      return true;
    });
  }
  look(clientX: number, clientY: number): void {
    const rect = this.host.getBoundingClientRect();
    this.headX = THREE.MathUtils.clamp((clientY - rect.top) / rect.height - 0.4, -0.3, 0.3);
    this.headY = THREE.MathUtils.clamp((clientX - rect.left) / rect.width - 0.5, -0.4, 0.4);
  }
  private resize(): void {
    const width = this.host.clientWidth || 480, height = this.host.clientHeight || 450;
    this.renderer.setSize(width, height); this.camera.aspect = width / height; this.camera.updateProjectionMatrix();
  }
  private animate(): void {
    const now = performance.now();
    if (now - this.previous < 1000 / 30) return;
    const delta = Math.min((now - this.previous) / 1000, 0.1); this.previous = now;
    if (this.motion) this.elapsed += delta;
    if (this.model && this.animations) {
      const model = this.model, clips = this.animations.animations;
      model.reset();
      const poseTime = this.elapsed - this.poseStart;
      const atDesk = this.mode === 'game' && (this.pose === 'idle' || this.pose === 'wave');
      this.workstation.root.visible = atDesk;
      if (atDesk) {
        model.apply(clips.pre_parallel0, this.elapsed);
        model.apply(clips.computer, this.elapsed);
        model.bones.get('Root')!.position.y = 0;
        const activity = this.workstation.animate(this.elapsed, this.motion);
        const mouseArm = model.bones.get('LeftForeArm')!;
        const target = model.root.localToWorld(activity.hand.clone());
        reachArm(model.bones.get('RightArm')!, model.bones.get('RightForeArm')!, model.bones.get('RightHand')!, target);
        mouseArm.rotation.y = 0.25;
        mouseArm.rotation.z = -0.05 + activity.mouseX * 0.04;
        mouseArm.rotation.x += activity.mouse * 0.08 + activity.mouseZ * 0.03;
        model.bones.get('LeftArm')!.position.x -= 0.5;
      } else if (this.pose === 'idle' || this.pose === 'wave') {
        model.apply(clips.pre_parallel0, this.elapsed);
        model.apply(clips.parallel2, this.elapsed);
        model.apply(clips.idle, this.elapsed);
        const root = model.bones.get('MAllbody'); if (root) root.position.y += Math.sin(this.elapsed * 1.5) * 0.12;
        const head = model.bones.get('Head'); if (head) { head.rotation.x += this.headX * 0.3; head.rotation.y += this.headY * 0.45; }
      } else if (this.pose === 'sit') {
        model.apply(clips.pre_parallel0, this.elapsed); model.apply(clips.sit, poseTime);
      } else {
        // The game's sleeping clip expects its bed transform. Rest seated on the desktop instead.
        model.apply(clips.sit, 0);
        const head = model.bones.get('Head');
        if (head) { head.rotation.z += 0.15; head.rotation.x += 0.12; }
        model.bones.get('RightEyelid')?.scale.setScalar(0);
        model.bones.get('LeftEyelid')?.scale.setScalar(0);
      }
      if (this.pose === 'wave') {
        const arm = model.bones.get('RightArm'), forearm = model.bones.get('RightForeArm');
        if (arm && forearm) { arm.rotation.z += 2.2; arm.rotation.x -= 0.15; forearm.rotation.x -= 0.5; forearm.rotation.z += Math.sin(poseTime * 8) * 0.25; }
      }
      model.root.scale.setScalar(this.scale);
      // Keep the feet of every pose at the same desktop baseline.
      model.root.position.y = 0;
      model.root.updateMatrixWorld(true);
      this.bounds.setFromObject(model.bones.get('AllBody')!, true);
      model.root.position.y = atDesk ? 0 : -this.bounds.min.y;
      if (this.pose !== 'sleep') {
        const blink = this.motion && this.elapsed % 5.3 > 5.12;
        for (const name of ['RightEyelid', 'LeftEyelid']) if (blink) model.bones.get(name)?.scale.setScalar(0.02);
      }
    }
    this.renderer.render(this.scene, this.camera);
  }
}
