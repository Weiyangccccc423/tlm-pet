import * as THREE from 'three';
import { animationValue } from './expression';
export type Vec3 = [number, number, number];
interface Cube { origin: Vec3; size: Vec3; uv: [number, number]; inflate?: number; pivot?: Vec3; rotation?: Vec3; mirror?: boolean }
interface Bone { name: string; parent?: string; pivot: Vec3; rotation?: Vec3; cubes?: Cube[] }
export interface GeometryDocument { 'minecraft:geometry': { description: { texture_width: number; texture_height: number }; bones: Bone[] }[] }
type Value = number | string | (number | string)[];
type Keyframe = Value | { pre?: Value; post?: Value; lerp_mode?: string };
type Channel = Value | Record<string, Keyframe>;
interface AnimatedBone { rotation?: Channel; position?: Channel; scale?: Channel }
export interface Clip { animation_length?: number; loop?: boolean | string; bones: Record<string, AnimatedBone> }
export interface AnimationDocument { animations: Record<string, Clip> }

const vector = (value: Value | undefined, fallback: number, time = 0): Vec3 => {
  if (Array.isArray(value)) return value.map(n => animationValue(n, time, fallback)) as Vec3;
  const n = value !== undefined ? animationValue(value, time, fallback) : fallback;
  return [n, n, n];
};
const keyValue = (frame: Keyframe, side: 'pre' | 'post', fallback: number, time: number): Vec3 =>
  vector(typeof frame === 'object' && !Array.isArray(frame) ? frame[side] ?? frame.post ?? frame.pre : frame, fallback, time);

/** Sample Bedrock numeric channels, including discontinuous pre/post keys and Catmull-Rom. */
export function sampleChannel(channel: Channel, time: number, fallback: number): Vec3 {
  if (typeof channel !== 'object' || Array.isArray(channel)) return vector(channel, fallback, time);
  const keys = Object.entries(channel).map(([time, value]) => ({ time: Number(time), value })).sort((a, b) => a.time - b.time);
  if (!keys.length) return vector(undefined, fallback);
  if (time < keys[0].time) return keyValue(keys[0].value, 'pre', fallback, time);
  let left = 0;
  while (left + 1 < keys.length && keys[left + 1].time <= time) left++;
  if (left === keys.length - 1) return keyValue(keys[left].value, 'post', fallback, time);
  const a = keyValue(keys[left].value, 'post', fallback, time);
  const b = keyValue(keys[left + 1].value, 'pre', fallback, time);
  const fraction = (time - keys[left].time) / (keys[left + 1].time - keys[left].time);
  const frame = keys[left + 1].value;
  if (typeof frame === 'object' && !Array.isArray(frame) && frame.lerp_mode === 'catmullrom') {
    const previous = keyValue(keys[Math.max(0, left - 1)].value, 'post', fallback, time);
    const next = keyValue(keys[Math.min(keys.length - 1, left + 2)].value, 'pre', fallback, time);
    return a.map((n, i) => {
      const p = previous[i], q = b[i], r = next[i], t = fraction;
      return 0.5 * ((2 * n) + (-p + q) * t + (2 * p - 5 * n + 4 * q - r) * t * t + (-p + 3 * n - 3 * q + r) * t * t * t);
    }) as Vec3;
  }
  return a.map((n, i) => THREE.MathUtils.lerp(n, b[i], fraction)) as Vec3;
}

export class BedrockModel {
  readonly root = new THREE.Group();
  readonly bones = new Map<string, THREE.Group>();
  private bases = new Map<string, { position: THREE.Vector3; rotation: THREE.Euler }>();
  constructor(document: GeometryDocument, texture: THREE.Texture) {
    const geometry = document['minecraft:geometry'][0];
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.generateMipmaps = false;
    const material = new THREE.MeshLambertMaterial({ map: texture, transparent: false, alphaTest: 0.08, side: THREE.DoubleSide });
    const definitions = new Map(geometry.bones.map(bone => [bone.name, bone]));
    for (const bone of geometry.bones) this.bones.set(bone.name, new THREE.Group());
    for (const bone of geometry.bones) {
      const group = this.bones.get(bone.name)!;
      group.name = bone.name;
      const parentPivot = definitions.get(bone.parent ?? '')?.pivot ?? [0, 0, 0];
      group.position.set(-(bone.pivot[0] - parentPivot[0]), bone.pivot[1] - parentPivot[1], bone.pivot[2] - parentPivot[2]);
      const rotation = bone.rotation ?? [0, 0, 0];
      group.rotation.set(-THREE.MathUtils.degToRad(rotation[0]), -THREE.MathUtils.degToRad(rotation[1]), THREE.MathUtils.degToRad(rotation[2]), 'ZYX');
      this.bases.set(bone.name, { position: group.position.clone(), rotation: group.rotation.clone() });
      (this.bones.get(bone.parent ?? '') ?? this.root).add(group);
      for (const cube of bone.cubes ?? []) {
        const inflate = cube.inflate ?? 0;
        const box = new THREE.BoxGeometry(...cube.size.map(n => Math.max(0.001, n + inflate * 2)) as Vec3);
        const [u, v] = cube.uv, [w, h, d] = cube.size;
        // Three's six faces: east, west, up, down, south, north, with X mirrored from Bedrock.
        const rectangles = [
          [u, v + d, d, h], [u + d + w, v + d, d, h],
          [u + d, v, w, d], [u + d + w, v + d, w, -d],
          [u + 2 * d + w, v + d, w, h], [u + d, v + d, w, h],
        ];
        const uv = box.attributes.uv;
        rectangles.forEach(([x, y, width, height], face) => {
          let left = x / geometry.description.texture_width, right = (x + width) / geometry.description.texture_width;
          if (cube.mirror) [left, right] = [right, left];
          const top = 1 - y / geometry.description.texture_height, bottom = 1 - (y + height) / geometry.description.texture_height;
          uv.setXY(face * 4, left, top); uv.setXY(face * 4 + 1, right, top);
          uv.setXY(face * 4 + 2, left, bottom); uv.setXY(face * 4 + 3, right, bottom);
        });
        const mesh = new THREE.Mesh(box, material);
        const pivot = cube.pivot ?? bone.pivot;
        mesh.position.set(-(cube.origin[0] + w / 2 - pivot[0]), cube.origin[1] + h / 2 - pivot[1], cube.origin[2] + d / 2 - pivot[2]);
        const pivotGroup = new THREE.Group();
        pivotGroup.position.set(-(pivot[0] - bone.pivot[0]), pivot[1] - bone.pivot[1], pivot[2] - bone.pivot[2]);
        const rot = cube.rotation ?? [0, 0, 0];
        pivotGroup.rotation.set(-THREE.MathUtils.degToRad(rot[0]), -THREE.MathUtils.degToRad(rot[1]), THREE.MathUtils.degToRad(rot[2]), 'ZYX');
        pivotGroup.add(mesh); group.add(pivotGroup);
      }
    }
    for (const name of ['FOX', 'Mask', 'kongju', 'jingya', 'xiao']) {
      const bone = this.bones.get(name); if (bone) bone.visible = false;
    }
  }

  reset(): void {
    for (const [name, group] of this.bones) {
      const base = this.bases.get(name)!;
      group.position.copy(base.position); group.rotation.copy(base.rotation); group.scale.setScalar(1);
    }
  }

  apply(clip: Clip, time: number, weight = 1): void {
    const localTime = clip.animation_length ? time % clip.animation_length : time;
    for (const [name, channels] of Object.entries(clip.bones)) {
      const group = this.bones.get(name); if (!group) continue;
      if (channels.rotation !== undefined) {
        const rotation = sampleChannel(channels.rotation, localTime, 0);
        group.rotation.x += -THREE.MathUtils.degToRad(rotation[0]) * weight;
        group.rotation.y += -THREE.MathUtils.degToRad(rotation[1]) * weight;
        group.rotation.z += THREE.MathUtils.degToRad(rotation[2]) * weight;
      }
      if (channels.position !== undefined) {
        const position = sampleChannel(channels.position, localTime, 0);
        group.position.x -= position[0] * weight; group.position.y += position[1] * weight; group.position.z += position[2] * weight;
      }
      if (channels.scale !== undefined) {
        const scale = sampleChannel(channels.scale, localTime, 1);
        group.scale.multiply(new THREE.Vector3(...scale.map(value => THREE.MathUtils.lerp(1, value, weight)) as Vec3));
      }
    }
  }
}
