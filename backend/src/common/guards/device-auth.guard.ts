import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { TokenService } from '../../auth/token.service';

function extractBearer(req: { headers?: Record<string, unknown> }): string | null {
  const header = req.headers?.authorization;
  if (typeof header !== 'string') return null;
  const [scheme, value] = header.split(' ');
  return scheme === 'Bearer' && value ? value : null;
}

/** Guards managed (child-device) routes. Requires a current enrolled device. */
@Injectable()
export class DeviceAuthGuard implements CanActivate {
  constructor(
    private readonly tokens: TokenService,
    private readonly prisma: PrismaService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest();
    const token = extractBearer(req);
    if (!token) throw new UnauthorizedException('missing bearer token');

    let payload;
    try {
      payload = await this.tokens.verify(token);
    } catch {
      throw new UnauthorizedException('invalid token');
    }
    if (payload.typ !== 'device') {
      throw new UnauthorizedException('wrong token type');
    }
    if (!payload.sub || !payload.fid) {
      throw new UnauthorizedException('invalid device token claims');
    }

    // Device tokens can live for a year. Check enrollment on each request so a
    // removed device is immediately revoked instead of retaining API access.
    const enrolled = await this.prisma.device.findUnique({
      where: { id: payload.sub },
      select: { id: true, familyId: true },
    });
    if (!enrolled || enrolled.familyId !== payload.fid) {
      throw new UnauthorizedException('device is no longer enrolled');
    }

    req.device = { deviceId: enrolled.id, familyId: enrolled.familyId };
    return true;
  }
}
