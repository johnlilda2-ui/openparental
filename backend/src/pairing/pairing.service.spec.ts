import { BadRequestException, NotFoundException } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { AuditService } from '../audit/audit.service';
import { PrismaService } from '../prisma/prisma.service';
import { TokenService } from '../auth/token.service';
import { DevicesService } from '../devices/devices.service';
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
    $transaction: jest.Mock;
  };
  let tx: {
    pairing: { update: jest.Mock; updateMany: jest.Mock };
  };
  let tokens: { signDevice: jest.Mock };
  let devices: { createForPairing: jest.Mock };
  let audit: { log: jest.Mock };

  beforeEach(async () => {
    prisma = {
      pairing: {
        create: jest.fn(),
        findFirst: jest.fn(),
        update: jest.fn(),
        updateMany: jest.fn(),
      },
      $transaction: jest.fn(),
    };
    tx = {
      pairing: {
        update: jest.fn().mockResolvedValue({}),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
      },
    };
    prisma.$transaction.mockImplementation((callback) => callback(tx));
    tokens = { signDevice: jest.fn().mockResolvedValue('device-token') };
    devices = {
      createForPairing: jest.fn().mockResolvedValue({
        device: { id: 'd1', familyId: 'f1', model: 'Pixel' },
        deviceSecret: 'secret',
      }),
    };
    audit = { log: jest.fn().mockResolvedValue(undefined) };

    const ref = await Test.createTestingModule({
      providers: [
        PairingService,
        { provide: PrismaService, useValue: prisma },
        { provide: TokenService, useValue: tokens },
        { provide: DevicesService, useValue: devices },
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

  it('claims only once inside a transaction and returns device credentials', async () => {
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
    expect(res.deviceSecret).toBe('secret');
    expect(prisma.$transaction).toHaveBeenCalledTimes(1);
    expect(tx.pairing.updateMany).toHaveBeenCalledWith({
      where: expect.objectContaining({
        id: 'p1',
        status: 'PENDING',
        expiresAt: expect.objectContaining({ gt: expect.any(Date) }),
      }),
      data: { status: 'CLAIMED' },
    });
    expect(devices.createForPairing).toHaveBeenCalledWith(
      'f1',
      expect.objectContaining({ model: 'Pixel' }),
      tx,
    );
    expect(tx.pairing.update).toHaveBeenCalledWith({
      where: { id: 'p1' },
      data: { claimedDeviceId: 'd1' },
    });
  });

  it('rejects a simultaneous claim after the conditional reservation loses', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      familyId: 'f1',
      status: 'PENDING',
      expiresAt: new Date(Date.now() + 60_000),
      deviceName: null,
    });
    tx.pairing.updateMany.mockResolvedValue({ count: 0 });

    await expect(service.claim({ token: 'tok' })).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(devices.createForPairing).not.toHaveBeenCalled();
  });

  it('claim rejects an unknown token', async () => {
    prisma.pairing.findFirst.mockResolvedValue(null);
    await expect(service.claim({ token: 'nope' })).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });

  it('claim rejects an already-used pairing', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      familyId: 'f1',
      status: 'CLAIMED',
      expiresAt: new Date(Date.now() + 60_000),
    });
    await expect(service.claim({ token: 'tok' })).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(prisma.$transaction).not.toHaveBeenCalled();
  });

  it('claim expires and rejects a stale pairing', async () => {
    prisma.pairing.findFirst.mockResolvedValue({
      id: 'p1',
      familyId: 'f1',
      status: 'PENDING',
      expiresAt: new Date(Date.now() - 1_000),
    });
    prisma.pairing.updateMany.mockResolvedValue({ count: 1 });
    await expect(service.claim({ token: 'tok' })).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(prisma.pairing.updateMany).toHaveBeenCalledWith({
      where: expect.objectContaining({
        id: 'p1',
        status: 'PENDING',
        expiresAt: expect.objectContaining({ lt: expect.any(Date) }),
      }),
      data: { status: 'EXPIRED' },
    });
  });

  it('does not expose pairing secrets when the pairing belongs to another family', async () => {
    prisma.pairing.findFirst.mockResolvedValue(null);
    await expect(service.getStatus('f1', 'other')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });
});
