import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { AnyPayload } from '../common/types';

const MIN_PRODUCTION_SECRET_LENGTH = 32;
const KNOWN_WEAK_SECRETS = new Set([
  'dev-secret-change-me',
  'change-me-in-prod-please-use-a-long-random-string',
  'secret',
  'password',
]);

@Injectable()
export class TokenService {
  private readonly secret: string;
  private readonly accessTtl: number;
  private readonly refreshTtl: number;
  private readonly deviceTtl: number;

  constructor(
    private readonly jwt: JwtService,
    config: ConfigService,
  ) {
    const secret = config.get<string>('jwt.secret') ?? '';
    const nodeEnv = config.get<string>('nodeEnv') ?? process.env.NODE_ENV ?? 'development';

    if (
      nodeEnv === 'production' &&
      (secret.trim().length < MIN_PRODUCTION_SECRET_LENGTH ||
        KNOWN_WEAK_SECRETS.has(secret.trim().toLowerCase()))
    ) {
      throw new Error(
        `JWT_SECRET must be a randomly generated secret of at least ${MIN_PRODUCTION_SECRET_LENGTH} characters in production`,
      );
    }

    this.secret = secret || 'dev-secret-change-me';
    this.accessTtl = config.get<number>('jwt.accessTtl') ?? 900;
    this.refreshTtl = config.get<number>('jwt.refreshTtl') ?? 2592000;
    this.deviceTtl = config.get<number>('jwt.deviceTtl') ?? 31536000;
  }

  signAccess(userId: string, familyId: string, role: string): Promise<string> {
    return this.jwt.signAsync(
      { sub: userId, fid: familyId, role, typ: 'access' },
      { secret: this.secret, expiresIn: this.accessTtl },
    );
  }

  signRefresh(userId: string, familyId: string): Promise<string> {
    return this.jwt.signAsync(
      { sub: userId, fid: familyId, typ: 'refresh' },
      { secret: this.secret, expiresIn: this.refreshTtl },
    );
  }

  signDevice(deviceId: string, familyId: string): Promise<string> {
    return this.jwt.signAsync(
      { sub: deviceId, fid: familyId, typ: 'device' },
      { secret: this.secret, expiresIn: this.deviceTtl },
    );
  }

  verify(token: string): Promise<AnyPayload> {
    return this.jwt.verifyAsync<AnyPayload>(token, { secret: this.secret });
  }
}
