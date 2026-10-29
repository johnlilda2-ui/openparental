import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { TokenService } from './token.service';

const config = (
  secret = 'test-secret',
  nodeEnv = 'test',
): ConfigService =>
  ({
    get: (k: string) => {
      if (k === 'jwt.secret') return secret;
      if (k === 'nodeEnv') return nodeEnv;
      return 900;
    },
  }) as unknown as ConfigService;

describe('TokenService', () => {
  let tokens: TokenService;

  beforeEach(() => {
    tokens = new TokenService(new JwtService({}), config());
  });

  it('signs and verifies an access token, preserving claims', async () => {
    const token = await tokens.signAccess('u1', 'f1', 'PARENT');
    const payload = (await tokens.verify(token)) as unknown as Record<string, unknown>;
    expect(payload.sub).toBe('u1');
    expect(payload.fid).toBe('f1');
    expect(payload.role).toBe('PARENT');
    expect(payload.typ).toBe('access');
  });

  it('tags refresh and device tokens with the right type', async () => {
    const refresh = (await tokens.verify(
      await tokens.signRefresh('u1', 'f1'),
    )) as unknown as Record<string, unknown>;
    const device = (await tokens.verify(
      await tokens.signDevice('d1', 'f1'),
    )) as unknown as Record<string, unknown>;
    expect(refresh.typ).toBe('refresh');
    expect(device.typ).toBe('device');
    expect(device.sub).toBe('d1');
  });

  it('rejects a token signed with a different secret', async () => {
    const other = new TokenService(new JwtService({}), config('other-secret'));
    const token = await other.signAccess('u', 'f', 'PARENT');
    await expect(tokens.verify(token)).rejects.toBeDefined();
  });

  it('rejects a missing JWT secret in production', () => {
    expect(
      () => new TokenService(new JwtService({}), config('', 'production')),
    ).toThrow(/JWT_SECRET must be a randomly generated secret/);
  });

  it('rejects a known placeholder JWT secret in production', () => {
    expect(
      () =>
        new TokenService(
          new JwtService({}),
          config('change-me-in-prod-please-use-a-long-random-string', 'production'),
        ),
    ).toThrow(/JWT_SECRET must be a randomly generated secret/);
  });

  it('rejects a short JWT secret in production', () => {
    expect(
      () => new TokenService(new JwtService({}), config('too-short', 'production')),
    ).toThrow(/JWT_SECRET must be a randomly generated secret/);
  });

  it('accepts a sufficiently long secret in production', () => {
    expect(
      () =>
        new TokenService(
          new JwtService({}),
          config('a-randomly-generated-production-secret-at-least-32-chars', 'production'),
        ),
    ).not.toThrow();
  });
});
