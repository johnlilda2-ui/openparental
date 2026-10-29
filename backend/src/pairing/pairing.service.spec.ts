import { BadRequestException, NotFoundException } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { AuditService } from '../audit/audit.service';
import { PrismaService } from '../prisma/prisma.service';
import { TokenService } from '../auth/token.service';
import { PairingService } from './pairing.service';

describe('PairingService', () => {
  let service: PairingService;
  let prisma: {
    pairing: {
      create: jest.Mock;
      findFirst: jest.Mock;
      update: jest.Mock;
      updateMany: jest.Mock;
    };
    device: { create: jest.Mock };
    $transaction: jest.Mock;
  };
  let tokens: { signDevice: jest.Mock };
  let audit: { log: jest.Mock };

  beforeEach(async () => {
    const tx = {
      pairing: {
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
        update: jest.fn().mockResolvedValue({}),
      },
      device: {
        create: jest.fn().mockResolvedValue({
          id: 'd1',
          familyId: 'f1',
          model: 'Pixel',
        }),
      },
    };
    prisma = {
      pairing: {
        create: jest.fn(),
        findFirst: jest.fn(),
        update: jest.fn().mockResolvedValue({}),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
      },
      device: { create: jest.fn() },
      $transaction: jest.fn((callback: (client: typeof tx) => unknown) =>
        callback(tx),
      ),
    };
    tokens = { signDevice: jest.fn().mockResolvedValue('device-token') };
    audit = { log: jest.fn().mockResolvedValue(undefined) };

    const ref = await Test.createTestingModule({
      providers: [
        PairingService,
        { provide: PrismaService, useValue: prisma },
        { provide: TokenService, useValue: tokens },
        { provide: AuditService, useValue: audit },
      ],
    }).compile();
    service = ref.get(PairingService);
  });

  it('create returns a human code and a QR token', async () => {
    prisma.pairing.create.mockImplementation(({ data }) =>
      Promise.resolve({ id: 'p1', ...data }),
    );
    const res = await service.create('f1', {});
    expect(res.code).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    expect(typeof res.qrToken).toBe('string');
    expect(res.expiresAt.getTime()).toBeGreaterThan(Date.now());
  });

  it('claims once inside a transaction and returns device credentials', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      familyId: 'f1',
      status: 'PENDING',
      expiresAt: new Date(Date.now() + 60_000),
      deviceName: null,
    });

    const res = await service.claim({ token: 'tok', model: 'Pixel' });
    expect(res.deviceToken).toBe('device-token');
    expect(res.deviceId).toBe('d1');
    expect(prisma.$transaction).toHaveBeenCalledTimes(1);
    expect(tokens.signDevice).toHaveBeenCalledWith('d1', 'f1');
    expect(audit.log).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'device_paired' }),
    );
  });

  it('claim rejects an unknown token', async () => {
    prisma.pairing.findFirst.mockResolvedValue(null);
    await expect(service.claim({ token: 'nope' })).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });

  it('claim rejects a pairing already marked as used', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      status: 'CLAIMED',
      expiresAt: new Date(Date.now() + 60_000),
    });
    await expect(service.claim({ token: 'tok' })).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(prisma.$transaction).not.toHaveBeenCalled();
  });

  it('marks expired pairings expired and rejects them', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      status: 'PENDING',
      expiresAt: new Date(Date.now() - 1_000),
    });
    await expect(service.claim({ token: 'tok' })).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(prisma.pairing.updateMany).toHaveBeenCalledWith({
      where: { id: 'p1', status: 'PENDING' },
      data: { status: 'EXPIRED' },
    });
    expect(prisma.$transaction).not.toHaveBeenCalled();
  });

  it('rejects a concurrent claimant when the conditional claim loses', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      familyId: 'f1',
      status: 'PENDING',
      expiresAt: new Date(Date.now() + 60_000),
      deviceName: null,
    });
    prisma.$transaction.mockImplementationOnce(
      async (callback: (client: {
        pairing: { updateMany: jest.Mock; update: jest.Mock };
        device: { create: jest.Mock };
      }) => Promise<unknown>) => {
        const tx = {
          pairing: {
            updateMany: jest.fn().mockResolvedValue({ count: 0 }),
            update: jest.fn(),
          },
          device: { create: jest.fn() },
        };
        return callback(tx);
      },
    );

    await expect(service.claim({ token: 'tok' })).rejects.toBeInstanceOf(
      BadRequestException,
    );
  });

  it('getStatus rejects a pairing from another family', async () => {
    prisma.pairing.findFirst.mockResolvedValue(null);
    await expect(service.getStatus('f1', 'other')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });
});
