import {
  BadRequestException,
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Command, CommandType, Prisma } from '@prisma/client';
import { AuditService } from '../audit/audit.service';
import { randomToken } from '../common/hash.util';
import { DevicesService } from '../devices/devices.service';
import { FcmService } from '../fcm/fcm.service';
import { PrismaService } from '../prisma/prisma.service';
import { CommandResultDto } from './dto/command-result.dto';
import { CreateCommandDto } from './dto/create-command.dto';

const HIGH_PRIORITY: ReadonlySet<CommandType> = new Set([
  CommandType.LOCK,
  CommandType.UNLOCK,
  CommandType.SCREEN_VIEW_REQUEST,
]);

const TERMINAL_STATES = new Set(['DONE', 'FAILED', 'EXPIRED']);
const PENDING_STATES = ['QUEUED', 'SENT'] as const;

@Injectable()
export class CommandsService {
  private readonly ttlSeconds: number;

  constructor(
    private readonly prisma: PrismaService,
    private readonly devices: DevicesService,
    private readonly fcm: FcmService,
    private readonly audit: AuditService,
    config: ConfigService,
  ) {
    this.ttlSeconds = config.get<number>('command.ttlSeconds') ?? 300;
  }

  async enqueue(
    familyId: string,
    deviceId: string,
    dto: CreateCommandDto,
  ): Promise<Command> {
    const device = await this.devices.getForFamily(familyId, deviceId);
    const idempotencyKey = dto.idempotencyKey ?? randomToken();

    const existing = await this.prisma.command.findUnique({
      where: { idempotencyKey },
    });
    if (existing) return this.validateIdempotentReuse(existing, deviceId, dto);

    let command: Command;
    try {
      command = await this.prisma.command.create({
        data: {
          deviceId,
          type: dto.type,
          payload: (dto.payload ?? undefined) as Prisma.InputJsonValue | undefined,
          idempotencyKey,
          state: 'QUEUED',
          expiresAt: new Date(Date.now() + this.ttlSeconds * 1000),
        },
      });
    } catch (error) {
      const raced = await this.prisma.command
        .findUnique({ where: { idempotencyKey } })
        .catch(() => null);
      if (raced) return this.validateIdempotentReuse(raced, deviceId, dto);
      throw error;
    }

    let sent = false;
    if (device.fcmToken) {
      try {
        sent = await this.fcm.sendData(
          device.fcmToken,
          { kind: 'command', commandId: command.id, type: command.type },
          HIGH_PRIORITY.has(command.type),
        );
      } catch {
        // Keep the command QUEUED so device polling can still deliver it.
      }
    }

    const updated = await this.prisma.command.update({
      where: { id: command.id },
      data: { state: sent ? 'SENT' : 'QUEUED' },
    });
    await this.audit.log({
      familyId,
      actor: 'parent',
      action: 'command_enqueued',
      data: { commandId: command.id, type: command.type },
    }).catch(() => undefined);
    return updated;
  }

  async listForFamilyDevice(
    familyId: string,
    deviceId: string,
  ): Promise<Command[]> {
    await this.devices.getForFamily(familyId, deviceId);
    return this.prisma.command.findMany({
      where: { deviceId },
      orderBy: { createdAt: 'desc' },
      take: 100,
    });
  }

  async getForFamily(familyId: string, commandId: string): Promise<Command> {
    const command = await this.prisma.command.findFirst({
      where: { id: commandId, device: { familyId } },
    });
    if (!command) throw new NotFoundException('command not found');
    return command;
  }

  /** Device pulls outstanding work (covers a missed FCM). Also reaps stale ones. */
  async pullPending(deviceId: string): Promise<Command[]> {
    await this.expireStale(deviceId);
    return this.prisma.command.findMany({
      where: { deviceId, state: { in: [...PENDING_STATES] } },
      orderBy: { createdAt: 'asc' },
    });
  }

  async ack(deviceId: string, commandId: string): Promise<Command> {
    const command = await this.ownedCommand(deviceId, commandId);
    if (command.state !== 'QUEUED' && command.state !== 'SENT') return command;

    const now = new Date();
    if (command.expiresAt.getTime() <= now.getTime()) {
      await this.prisma.command.updateMany({
        where: {
          id: commandId,
          deviceId,
          state: { in: [...PENDING_STATES] },
          expiresAt: { lte: now },
        },
        data: {
          state: 'EXPIRED',
          error: 'Command expired before the device acknowledged it',
        },
      });
      return this.ownedCommand(deviceId, commandId);
    }

    await this.prisma.command.updateMany({
      where: {
        id: commandId,
        deviceId,
        state: { in: [...PENDING_STATES] },
        expiresAt: { gt: now },
      },
      data: { state: 'ACKED' },
    });
    // Re-read the row so the client executes only after seeing the actual state.
    return this.ownedCommand(deviceId, commandId);
  }

  async complete(
    deviceId: string,
    commandId: string,
    dto: CommandResultDto,
  ): Promise<Command> {
    const command = await this.ownedCommand(deviceId, commandId);
    if (TERMINAL_STATES.has(command.state)) return command;

    const hasResult = dto.result !== undefined;
    const hasError = typeof dto.error === 'string' && dto.error.trim().length > 0;
    if (hasResult === hasError) {
      throw new BadRequestException('Provide exactly one of result or error');
    }

    const now = new Date();
    const resultDeadline = new Date(now.getTime() - this.ttlSeconds * 1000);
    const ackExpired =
      command.state === 'ACKED' &&
      command.updatedAt.getTime() <= resultDeadline.getTime();
    const pendingExpired =
      (command.state === 'QUEUED' || command.state === 'SENT') &&
      command.expiresAt.getTime() <= now.getTime();

    if (ackExpired || pendingExpired) {
      await this.prisma.command.updateMany({
        where: {
          id: commandId,
          deviceId,
          state: command.state,
        },
        data: {
          state: 'EXPIRED',
          error: 'Command expired before a terminal result was received',
        },
      });
      return this.ownedCommand(deviceId, commandId);
    }

    if (command.state !== 'ACKED') {
      throw new ConflictException('Command must be acknowledged before a result is accepted');
    }

    const update = await this.prisma.command.updateMany({
      where: {
        id: commandId,
        deviceId,
        state: 'ACKED',
        updatedAt: { gt: resultDeadline },
      },
      data: {
        state: hasError ? 'FAILED' : 'DONE',
        result: hasResult
          ? (dto.result as Prisma.InputJsonValue)
          : undefined,
        error: hasError ? dto.error!.trim() : null,
      },
    });
    // A concurrent completion/expiry can win; return the authoritative state.
    if (update.count !== 1) return this.ownedCommand(deviceId, commandId);
    return this.ownedCommand(deviceId, commandId);
  }

  private async expireStale(deviceId: string): Promise<void> {
    const now = new Date();
    const resultDeadline = new Date(now.getTime() - this.ttlSeconds * 1000);
    await this.prisma.command.updateMany({
      where: {
        deviceId,
        OR: [
          {
            state: { in: [...PENDING_STATES] },
            expiresAt: { lt: now },
          },
          {
            state: 'ACKED',
            updatedAt: { lt: resultDeadline },
          },
        ],
      },
      data: {
        state: 'EXPIRED',
        error: 'Command expired before a terminal result was received',
      },
    });
  }

  private validateIdempotentReuse(
    existing: Command,
    deviceId: string,
    dto: CreateCommandDto,
  ): Command {
    if (existing.deviceId !== deviceId || existing.type !== dto.type) {
      throw new ConflictException(
        'Idempotency key is already associated with a different device or command type',
      );
    }
    return existing;
  }

  private async ownedCommand(
    deviceId: string,
    commandId: string,
  ): Promise<Command> {
    const command = await this.prisma.command.findFirst({
      where: { id: commandId, deviceId },
    });
    if (!command) throw new NotFoundException('command not found');
    return command;
  }
}
