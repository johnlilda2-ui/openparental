const deepmerge = require('deepmerge') as (
  target: Record<string, unknown>,
  source: Record<string, unknown>,
) => Record<string, unknown>;

describe('patched deepmerge prototype-key filtering', () => {
  afterEach(() => {
    delete (Object.prototype as { polluted?: unknown }).polluted;
  });

  it('does not mutate the returned object prototype for null-prototype targets', () => {
    const target = Object.create(null) as Record<string, unknown>;
    const source = JSON.parse(
      '{"__proto__":{"polluted":"yes"},"safe":{"nested":true}}',
    ) as Record<string, unknown>;

    const result = deepmerge(target, source);

    expect(Object.getPrototypeOf(result)).toBe(Object.prototype);
    expect(({} as { polluted?: unknown }).polluted).toBeUndefined();
    expect(result.safe).toEqual({ nested: true });
    expect(Object.prototype.hasOwnProperty.call(result, '__proto__')).toBe(false);
  });

  it('filters constructor, prototype and proto properties at every merge level', () => {
    const target = Object.create(null) as Record<string, unknown>;
    const source = JSON.parse(
      '{"constructor":{"prototype":{"polluted":"yes"}},"prototype":{"polluted":"yes"},"proto":{"polluted":"yes"},"safe":1}',
    ) as Record<string, unknown>;

    const result = deepmerge(target, source);

    expect(({} as { polluted?: unknown }).polluted).toBeUndefined();
    expect(result.safe).toBe(1);
    expect(Object.prototype.hasOwnProperty.call(result, 'constructor')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(result, 'prototype')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(result, 'proto')).toBe(false);
  });
});
