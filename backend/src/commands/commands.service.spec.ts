import { ConflictException, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import { AuditService } from '../audit/audit.service';
import { DevicesService } from '../devices/devices.service';
import { FcmService } from '../fcm/fcm.service';
import { PrismaService } from '../prisma/prisma.service';
import { CommandsService } from './commands.service';

describe('CommandsService', () => {
  let service: CommandsService;
  let prisma: {
    command: {
      findUnique: jest.Mock;
      create: jest.Mock;
      update: jest.Mock;
      findFirst: jest.Mock;
      findMany: jest.Mock;
      updateMany: jest.Mock;
    };
  };
  let devices: { getForFamily: jest.Mock };
  let fcm: { sendData: jest.Mock };
  let audit: { log: jest.Mock };

  beforeEach(async () => {
    prisma = {
      command: {
        findUnique: jest.fn(),
        create: jest.fn(),
        update: jest.fn(),
        findFirst: jest.fn(),
        findMany: jest.fn(),
        updateMany: jest.fn(),
      },
    };
    devices = {
      getForFamily: jest
        .fn()
        .mockResolvedValue({ id: 'd1', familyId: 'f1', fcmToken: 'tok' }),
    };
    fcm = { sendData: jest.fn().mockResolvedValue(true) };
    audit = { log: jest.fn().mockResolvedValue(undefined) };

    const ref = await Test.createTestingModule({
      providers: [
        CommandsService,
        { provide: PrismaService, useValue: prisma },
        { provide: DevicesService, useValue: devices },
        { provide: FcmService, useValue: fcm },
        { provide: AuditService, useValue: audit },
        { provide: ConfigService, useValue: { get: () => 300 } },
      ],
    }).compile();
    service = ref.get(CommandsService);
  });

  describe('enqueue', () => {
    it('creates, pushes high-priority FCM for LOCK, and marks SENT', async () => {
      prisma.command.findUnique.mockResolvedValue(null);
      prisma.command.create.mockResolvedValue({
        id: 'c1',
        deviceId: 'd1',
        type: 'LOCK',
        state: 'QUEUED',
      });
      prisma.command.update.mockResolvedValue({ id: 'c1', state: 'SENT' });

      const res = await service.enqueue('f1', 'd1', { type: 'LOCK' as never });

      expect(res.state).toBe('SENT');
      expect(fcm.sendData).toHaveBeenCalledWith(
        'tok',
        expect.objectContaining({ kind: 'command', commandId: 'c1' }),
        true,
      );
      expect(audit.log).toHaveBeenCalled();
    });

    it('is idempotent for the same device and command type', async () => {
      prisma.command.findUnique.mockResolvedValue({
        id: 'c0',
        deviceId: 'd1',
        type: 'PING',
        state: 'ACKED',
      });
      const res = await service.enqueue('f1', 'd1', {
        type: 'PING' as never,
        idempotencyKey: 'dup',
      });
      expect(res.id).toBe('c0');
      expect(prisma.command.create).not.toHaveBeenCalled();
    });

    it('rejects an idempotency key that belongs to another device', async () => {
      prisma.command.findUnique.mockResolvedValue({
        id: 'c0',
        deviceId: 'd2',
        type: 'PING',
        state: 'ACKED',
      });
      await expect(
        service.enqueue('f1', 'd1', {
          type: 'PING' as never,
          idempotencyKey: 'cross-device-key',
        }),
      ).rejects.toBeInstanceOf(ConflictException);
      expect(prisma.command.create).not.toHaveBeenCalled();
    });

    it('rejects reuse of a key for a different command type', async () => {
      prisma.command.findUnique.mockResolvedValue({
        id: 'c0',
        deviceId: 'd1',
        type: 'PING',
        state: 'ACKED',
      });
      await expect(
        service.enqueue('f1', 'd1', {
          type: 'LOCK' as never,
          idempotencyKey: 'same-device-other-command',
        }),
      ).rejects.toBeInstanceOf(ConflictException);
    });

    it('stays QUEUED when the device has no FCM token', async () => {
      devices.getForFamily.mockResolvedValue({
        id: 'd1',
        familyId: 'f1',
        fcmToken: null,
      });
      prisma.command.findUnique.mockResolvedValue(null);
      prisma.command.create.mockResolvedValue({ id: 'c1', type: 'PING' });
      prisma.command.update.mockResolvedValue({ id: 'c1', state: 'QUEUED' });

      const res = await service.enqueue('f1', 'd1', { type: 'PING' as never });
      expect(res.state).toBe('QUEUED');
      expect(fcm.sendData).not.toHaveBeenCalled();
    });
  });

  describe('ack', () => {
    it('moves SENT -> ACKED', async () => {
      prisma.command.findFirst.mockResolvedValue({ id: 'c1', state: 'SENT' });
      prisma.command.update.mockResolvedValue({ id: 'c1', state: 'ACKED' });
      const res = await service.ack('d1', 'c1');
      expect(res.state).toBe('ACKED');
    });

    it('is a no-op once terminal (duplicate delivery)', async () => {
      prisma.command.findFirst.mockResolvedValue({ id: 'c1', state: 'DONE' });
      const res = await service.ack('d1', 'c1');
      expect(res.state).toBe('DONE');
      expect(prisma.command.update).not.toHaveBeenCalled();
    });

    it('throws when the command is not the device own', async () => {
      prisma.command.findFirst.mockResolvedValue(null);
      await expect(service.ack('d1', 'nope')).rejects.toBeInstanceOf(
        NotFoundException,
      );
    });
  });

  describe('complete', () => {
    it('marks DONE on success', async () => {
      prisma.command.findFirst.mockResolvedValue({ id: 'c1', state: 'ACKED' });
      prisma.command.update.mockResolvedValue({ id: 'c1', state: 'DONE' });
      const res = await service.complete('d1', 'c1', { result: { ok: true } });
      expect(res.state).toBe('DONE');
    });

    it('marks FAILED when an error is reported', async () => {
      prisma.command.findFirst.mockResolvedValue({ id: 'c1', state: 'ACKED' });
      prisma.command.update.mockResolvedValue({ id: 'c1', state: 'FAILED' });
      const res = await service.complete('d1', 'c1', { error: 'boom' });
      expect(res.state).toBe('FAILED');
      expect(prisma.command.update).toHaveBeenCalledWith(
        expect.objectContaining({
          data: expect.objectContaining({ state: 'FAILED', error: 'boom' }),
        }),
      );
    });

    it('does not replace an expired command state with a late result', async () => {
      prisma.command.findFirst.mockResolvedValue({ id: 'c1', state: 'EXPIRED' });
      const res = await service.complete('d1', 'c1', { result: { ok: true } });
      expect(res.state).toBe('EXPIRED');
      expect(prisma.command.update).not.toHaveBeenCalled();
    });
  });

  describe('pullPending', () => {
    it('expires stale queued, sent, and acknowledged commands before listing pending ones', async () => {
      prisma.command.updateMany.mockResolvedValue({ count: 1 });
      prisma.command.findMany.mockResolvedValue([{ id: 'c1', state: 'QUEUED' }]);
      const res = await service.pullPending('d1');
      expect(prisma.command.updateMany).toHaveBeenCalledWith(
        expect.objectContaining({
          where: expect.objectContaining({
            OR: expect.arrayContaining([
              expect.objectContaining({ state: { in: ['QUEUED', 'SENT'] } }),
              expect.objectContaining({ state: 'ACKED' }),
            ]),
          }),
          data: expect.objectContaining({
            state: 'EXPIRED',
            error: expect.any(String),
          }),
        }),
      );
      expect(res).toHaveLength(1);
    });
  });

  describe('getForFamily', () => {
    it('throws when the command is in another family', async () => {
      prisma.command.findFirst.mockResolvedValue(null);
      await expect(service.getForFamily('f1', 'nope')).rejects.toBeInstanceOf(
        NotFoundException,
      );
    });
  });
});
