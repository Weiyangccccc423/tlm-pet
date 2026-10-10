type Expression = (time: number) => number;
const cache = new Map<string, Expression | null>();

/** Small Molang arithmetic subset needed by the computer clip; never executes JavaScript. */
function compile(source: string): Expression {
  if (source.length > 256) throw new Error('Expression too long');
  const tokens = source.match(/\d*\.?\d+(?:[eE][+-]?\d+)?|query\.anim_time|math\.(?:sin|cos)|[()+*/-]|\S/g) ?? [];
  let offset = 0;
  const expect = (token: string) => { if (tokens[offset++] !== token) throw new Error('Unsupported expression'); };
  function atom(): Expression {
    const token = tokens[offset++];
    if (token === '+') return atom();
    if (token === '-') { const value = atom(); return time => -value(time); }
    if (token === '(') { const value = sum(); expect(')'); return value; }
    if (token === 'query.anim_time') return time => time;
    if (token === 'math.sin' || token === 'math.cos') {
      expect('('); const argument = sum(); expect(')');
      return time => (token === 'math.sin' ? Math.sin : Math.cos)(argument(time) * Math.PI / 180);
    }
    if (token !== undefined && /^\d*\.?\d+(?:[eE][+-]?\d+)?$/.test(token)) { const value = Number(token); return () => value; }
    throw new Error('Unsupported expression');
  }
  function product(): Expression {
    let value = atom();
    while (tokens[offset] === '*' || tokens[offset] === '/') {
      const operator = tokens[offset++], left = value, right = atom();
      value = time => operator === '*' ? left(time) * right(time) : left(time) / right(time);
    }
    return value;
  }
  function sum(): Expression {
    let value = product();
    while (tokens[offset] === '+' || tokens[offset] === '-') {
      const operator = tokens[offset++], left = value, right = product();
      value = time => operator === '+' ? left(time) + right(time) : left(time) - right(time);
    }
    return value;
  }
  const result = sum(); if (offset !== tokens.length) throw new Error('Unsupported expression');
  return result;
}

export function animationValue(value: number | string, time: number, fallback: number): number {
  if (typeof value === 'number') return Number.isFinite(value) ? value : fallback;
  if (!cache.has(value)) { try { cache.set(value, compile(value)); } catch { cache.set(value, null); } }
  const result = cache.get(value)?.(time);
  return result !== undefined && Number.isFinite(result) ? result : fallback;
}
