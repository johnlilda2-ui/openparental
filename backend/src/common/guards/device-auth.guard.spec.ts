import { ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { TokenService } from '../../auth/token.service';
import { PrismaService } from '../../prisma/prisma.service';
import { DeviceAuthGuard } from './device-auth.guard';

function context(headers: Record<string, unknown>): {
  ctx: ExecutionContext;
  req: Record<string, unknown>;
} {
  const req: Record<string, unknown> = { headers };
  const ctx = {
    switchToHttp: () => ({ getRequest: () => req }),
  } as unknown as ExecutionContext;
  return { ctx, req };
}

describe('DeviceAuthGuard', () => {
  const tokens = { verify: jest.fn() } as unknown as TokenService;
  const prisma = {
    device: { findUnique: jest.fn() },
  } as unknown as PrismaService;
  let guard: DeviceAuthGuard;

  beforeEach(() => {
    jest.clearAllMocks();
    guard = new DeviceAuthGuard(tokens, prisma);
    (prisma.device.findUnique as jest.Mock).mockResolvedValue({
      id: 'd1',
      familyId: 'f1',
    });
  });

  it('allows a valid token for a currently enrolled device', async () => {
    (tokens.verify as jest.Mock).mockResolvedValue({
      sub: 'd1',
      fid: 'f1',
      typ: 'device',
    });
    const { ctx, req } = context({ authorization: 'Bearer good' });
    await expect(guard.canActivate(ctx)).resolves.toBe(true);
    expect(req.device).toEqual({ deviceId: 'd1', familyId: 'f1' });
    expect(prisma.device.findUnique).toHaveBeenCalledWith({
      where: { id: 'd1' },
      select: { id: true, familyId: true },
    });
  });

  it('rejects a token after the device has been removed', async () => {
    (tokens.verify as jest.Mock).mockResolvedValue({
      sub: 'd1',
      fid: 'f1',
      typ: 'device',
    });
    (prisma.device.findUnique as jest.Mock).mockResolvedValue(null);
    const { ctx } = context({ authorization: 'Bearer removed-device' });
    await expect(guard.canActivate(ctx)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
  });

  it('rejects a token whose family claim no longer matches the device', async () => {
    (tokens.verify as jest.Mock).mockResolvedValue({
      sub: 'd1',
      fid: 'f2',
      typ: 'device',
    });
    const { ctx } = context({ authorization: 'Bearer wrong-family' });
    await expect(guard.canActivate(ctx)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
  });

  it('rejects an access token on a device route', async () => {
    (tokens.verify as jest.Mock).mockResolvedValue({ sub: 'u1', typ: 'access' });
    const { ctx } = context({ authorization: 'Bearer parent' });
    await expect(guard.canActivate(ctx)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
    expect(prisma.device.findUnique).not.toHaveBeenCalled();
  });

  it('rejects a missing token', async () => {
    const { ctx } = context({});
    await expect(guard.canActivate(ctx)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
  });
});
