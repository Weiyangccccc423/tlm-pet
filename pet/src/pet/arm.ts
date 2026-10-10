import * as THREE from 'three';

/** Aim the existing shoulder/elbow chain without moving bones or stretching the sleeves. */
export function reachArm(upper: THREE.Object3D, lower: THREE.Object3D, hand: THREE.Object3D, target: THREE.Vector3): void {
  upper.updateWorldMatrix(true, true);
  const shoulder = upper.getWorldPosition(new THREE.Vector3());
  const elbow = lower.getWorldPosition(new THREE.Vector3());
  const palm = hand.getWorldPosition(new THREE.Vector3());
  const upperLength = shoulder.distanceTo(elbow), lowerLength = elbow.distanceTo(palm);
  if (upperLength < 0.001 || lowerLength < 0.001) return;
  const direction = target.clone().sub(shoulder);
  const distance = THREE.MathUtils.clamp(direction.length(), Math.abs(upperLength - lowerLength) + 0.001, upperLength + lowerLength - 0.001);
  if (direction.lengthSq() < 0.000001) direction.set(0, 0, -1);
  direction.normalize();
  // Keep the elbow on the same side as the authored computer pose.
  const bend = elbow.clone().sub(shoulder);
  bend.addScaledVector(direction, -bend.dot(direction));
  if (bend.lengthSq() < 0.000001) {
    bend.set(0, 1, 0).addScaledVector(direction, -direction.y);
    if (bend.lengthSq() < 0.000001) bend.set(1, 0, 0);
  }
  bend.normalize();
  const along = (upperLength ** 2 + distance ** 2 - lowerLength ** 2) / (2 * distance);
  const across = Math.sqrt(Math.max(0, upperLength ** 2 - along ** 2));
  const nextElbow = shoulder.clone().addScaledVector(direction, along).addScaledVector(bend, across);
  const reachable = shoulder.clone().addScaledVector(direction, distance);
  rotateToward(upper, elbow.clone().sub(shoulder), nextElbow.clone().sub(shoulder));
  upper.updateWorldMatrix(true, true);
  const updatedElbow = lower.getWorldPosition(new THREE.Vector3());
  rotateToward(lower, hand.getWorldPosition(new THREE.Vector3()).sub(updatedElbow), reachable.sub(updatedElbow));
  lower.updateWorldMatrix(true, true);
}

function rotateToward(bone: THREE.Object3D, from: THREE.Vector3, to: THREE.Vector3): void {
  const delta = new THREE.Quaternion().setFromUnitVectors(from.normalize(), to.normalize());
  const orientation = delta.multiply(bone.getWorldQuaternion(new THREE.Quaternion()));
  const parent = bone.parent?.getWorldQuaternion(new THREE.Quaternion()) ?? new THREE.Quaternion();
  bone.quaternion.copy(parent.invert().multiply(orientation));
}
