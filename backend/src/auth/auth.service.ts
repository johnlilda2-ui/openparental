import {
  BadRequestException,
  ConflictException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import * as bcrypt from 'bcrypt';
import { PrismaService } from '../prisma/prisma.service';
import { safeEqual, sha256 } from '../common/hash.util';
import { TokenService } from './token.service';
import { RegisterDto } from './dto/register.dto';
import { LoginDto } from './dto/login.dto';
import { RefreshDto } from './dto/refresh.dto';

const BCRYPT_ROUNDS = 10;

export interface AuthResult {
  accessToken: string;
  refreshToken: string;
  user: { id: string; email: string; familyId: string; role: string };
}

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly tokens: TokenService,
  ) {}

  async register(dto: RegisterDto): Promise<AuthResult> {
    if (Buffer.byteLength(dto.password, 'utf8') > 72) {
      throw new BadRequestException(
        'Password must be at most 72 UTF-8 bytes because bcrypt cannot safely hash longer values',
      );
    }
    const existing = await this.prisma.user.findUnique({
      where: { email: dto.email },
    });
    if (existing) throw new ConflictException('email already registered');

    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_ROUNDS);
    let user;
    try {
      user = await this.prisma.user.create({
        data: {
          email: dto.email,
          passwordHash,
          family: { create: { name: dto.familyName ?? `${dto.email}'s family` } },
        },
      });
    } catch (error) {
      // The pre-check is only a friendly fast path; enforce uniqueness under
      // concurrent registrations using the database's unique constraint too.
      if (
        typeof error === 'object' &&
        error !== null &&
        'code' in error &&
        error.code === 'P2002'
      ) {
        throw new ConflictException('email already registered');
      }
      throw error;
    }
    return this.issue(user);
  }

  async login(dto: LoginDto): Promise<AuthResult> {
    if (Buffer.byteLength(dto.password, 'utf8') > 72) {
      throw new UnauthorizedException('invalid credentials');
    }
    const user = await this.prisma.user.findUnique({
      where: { email: dto.email },
    });
    if (!user) throw new UnauthorizedException('invalid credentials');

    const ok = await bcrypt.compare(dto.password, user.passwordHash);
    if (!ok) throw new UnauthorizedException('invalid credentials');

    return this.issue(user);
  }

  async refresh(dto: RefreshDto): Promise<AuthResult> {
    let payload;
    try {
      payload = await this.tokens.verify(dto.refreshToken);
    } catch {
      throw new UnauthorizedException('invalid refresh token');
    }
    if (payload.typ !== 'refresh') {
      throw new UnauthorizedException('wrong token type');
    }

    const user = await this.prisma.user.findUnique({ where: { id: payload.sub } });
    if (!user || !user.refreshTokenHash) {
      throw new UnauthorizedException('refresh token revoked');
    }
    const previousHash = user.refreshTokenHash;
    if (!safeEqual(sha256(dto.refreshToken), previousHash)) {
      throw new UnauthorizedException('refresh token mismatch');
    }

    // Compare-and-swap means concurrent requests with one refresh token cannot
    // both succeed and return competing refresh tokens.
    return this.issue(user, previousHash);
  }

  async logout(userId: string): Promise<void> {
    await this.prisma.user.update({
      where: { id: userId },
      data: { refreshTokenHash: null },
    });
  }

  /** Register the parent device's FCM token so alerts can reach them. */
  async setFcmToken(userId: string, fcmToken: string): Promise<void> {
    await this.prisma.user.update({
      where: { id: userId },
      data: { fcmToken },
    });
  }

  /** Issue an access+refresh pair and persist its hash. */
  private async issue(
    user: {
      id: string;
      email: string;
      familyId: string;
      role: string;
    },
    expectedPreviousHash?: string,
  ): Promise<AuthResult> {
    const accessToken = await this.tokens.signAccess(
      user.id,
      user.familyId,
      user.role,
    );
    const refreshToken = await this.tokens.signRefresh(user.id, user.familyId);
    const nextHash = sha256(refreshToken);

    if (expectedPreviousHash !== undefined) {
      const result = await this.prisma.user.updateMany({
        where: { id: user.id, refreshTokenHash: expectedPreviousHash },
        data: { refreshTokenHash: nextHash },
      });
      if (result.count !== 1) {
        throw new UnauthorizedException('refresh token has already been used');
      }
    } else {
      await this.prisma.user.update({
        where: { id: user.id },
        data: { refreshTokenHash: nextHash },
      });
    }

    return {
      accessToken,
      refreshToken,
      user: {
        id: user.id,
        email: user.email,
        familyId: user.familyId,
        role: user.role,
      },
    };
  }
}
