import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { reachArm } from '../src/pet/arm.js';

test('arm reaches targets through rotated/scaled parents without stretching either bone', () => {
  const root = new THREE.Group(); root.position.set(4, 8, -3); root.rotation.set(0.1, 0.4, -0.2); root.scale.setScalar(1.3);
  const upper = new THREE.Group(), lower = new THREE.Group(), hand = new THREE.Group();
  root.add(upper); upper.add(lower); lower.add(hand);
  lower.position.set(0, -5.5, 0); hand.position.set(0, -7.3, 0); lower.rotation.x = 1.1;
  const upperPosition = upper.position.clone(), lowerPosition = lower.position.clone(), handPosition = hand.position.clone();
  for (const local of [[-0.6, -5.5, -10.5], [4.5, -5.5, -10.5], [3.2, -5.5, -5.3]]) {
    const target = root.localToWorld(new THREE.Vector3(...local));
    reachArm(upper, lower, hand, target);
    assert.ok(hand.getWorldPosition(new THREE.Vector3()).distanceTo(target) < 0.001);
    assert.deepEqual(upper.position, upperPosition); assert.deepEqual(lower.position, lowerPosition); assert.deepEqual(hand.position, handPosition);
    assert.equal(upper.scale.x, 1); assert.equal(lower.scale.x, 1);
  }
});

test('unreachable and coincident targets remain finite and within arm reach', () => {
  const upper = new THREE.Group(), lower = new THREE.Group(), hand = new THREE.Group();
  upper.add(lower); lower.add(hand); lower.position.y = -5; hand.position.y = -7;
  for (const target of [new THREE.Vector3(0, 0, -100), new THREE.Vector3(), new THREE.Vector3(0, -7, 0)]) {
    reachArm(upper, lower, hand, target);
    const palm = hand.getWorldPosition(new THREE.Vector3());
    assert.ok(palm.toArray().every(Number.isFinite)); assert.ok(palm.length() <= 12.001);
  }
});
