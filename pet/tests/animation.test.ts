import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sampleChannel } from '../src/pet/bedrock.js';
import { animationValue } from '../src/pet/expression.js';

test('Bedrock interpolation preserves discontinuous pre/post keys and static scale', () => {
  const channel = { '0': [0, 0, 0], '1': { pre: [10, 20, 30], post: [40, 50, 60] }, '2': [50, 60, 70] };
  assert.deepEqual(sampleChannel(channel, 0.5, 0), [5, 10, 15]);
  assert.deepEqual(sampleChannel(channel, 1, 0), [40, 50, 60]);
  assert.deepEqual(sampleChannel(channel, 1.5, 0), [45, 55, 65]);
  assert.deepEqual(sampleChannel(0, 1, 1), [0, 0, 0]);
  assert.deepEqual(sampleChannel('unsupported.expression', 1, 1), [1, 1, 1]);
});

test('Catmull-Rom uses neighboring keyframes instead of linear interpolation', () => {
  const channel = { '0': [0, 0, 0], '1': { post: [10, 10, 10], lerp_mode: 'catmullrom' }, '2': { post: [10, 10, 10], lerp_mode: 'catmullrom' }, '3': [0, 0, 0] };
  assert.deepEqual(sampleChannel(channel, 1.5, 0), [11.25, 11.25, 11.25]);
});

test('computer animation evaluates time and degree-based Molang arithmetic without executing code', () => {
  assert.equal(animationValue('math.sin(query.anim_time * 90) * 3 + 2', 1, 0), 5);
  assert.equal(animationValue('-(2 + 3) * math.cos(180) / 2', 0, 0), 2.5);
  assert.equal(animationValue('+1e2 - 2 * (3 + .5)', 0, 0), 93);
  assert.deepEqual(sampleChannel(['query.anim_time * 2', 'math.sin(90)', '-3'], 1.5, 0), [3, 1, -3]);
  for (const value of ['1/0', 'globalThis.process.exit()', 'math.sin(90);alert(1)', '', '1+'.repeat(200), NaN]) {
    assert.equal(animationValue(value, 1, 7), 7);
  }
});
