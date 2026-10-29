import { buildConfiguration } from './configuration';

describe('buildConfiguration', () => {
  it('keeps the development secret fallback outside production', () => {
    const config = buildConfiguration({
      NODE_ENV: 'test',
    } as NodeJS.ProcessEnv);
    expect(config.jwt.secret).toBe('dev-secret-change-me');
  });

  it('refuses to configure production with no JWT secret', () => {
    expect(() =>
      buildConfiguration({ NODE_ENV: 'production' } as NodeJS.ProcessEnv),
    ).toThrow(/JWT_SECRET must be configured/);
  });

  it('refuses a short production JWT secret', () => {
    expect(() =>
      buildConfiguration({
        NODE_ENV: 'production',
        JWT_SECRET: 'short',
      } as NodeJS.ProcessEnv),
    ).toThrow(/at least 32 characters/);
  });

  it('accepts a sufficiently long production JWT secret', () => {
    const config = buildConfiguration({
      NODE_ENV: 'production',
      JWT_SECRET: 'a'.repeat(48),
    } as NodeJS.ProcessEnv);
    expect(config.jwt.secret).toBe('a'.repeat(48));
  });
});
