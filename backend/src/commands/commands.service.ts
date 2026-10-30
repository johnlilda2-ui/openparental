import { Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Command, CommandType, Prisma } from '@prisma/client';
import { AuditService } from '../audit/audit.service';
import { randomToken } from '../common/hash.util';
import { DevicesService } from '../devices/devices.service';
import { FcmService } from '../fcm/fcm.service';
import { PrismaService } from '../prisma/prisma.service';
import { CommandResultDto } from './dto/command-result.dto';
import { CreateCommandDto } from './dto/create-command.dto';

const POLICY_COMMAND_TTL_SECONDS = 7 * 24 * 60 * 60;
const HIGH_PRIORITY: ReadonlySet<CommandType> = new Set([CommandType.LOCK, CommandType.UNLOCK, CommandType.SCREEN_VIEW_REQUEST]);

@Injectable()
export class CommandsService {
  private readonly ttlSeconds: number;
  constructor(
    private readonly prisma: PrismaService,
    private readonly devices: DevicesService,
    private readonly fcm: FcmService,
    private readonly audit: AuditService,
    config: ConfigService,
  ) { this.ttlSeconds = config.get<number>('command.ttlSeconds') ?? 300; }

  async enqueue(familyId: string, deviceId: string, dto: CreateCommandDto): Promise<Command> {
    const device = await this.devices.getForFamily(familyId, deviceId);
    const idempotencyKey = dto.idempotencyKey ?? randomToken();
    const existing = await this.prisma.command.findUnique({ where: { idempotencyKey } });
    if (existing) return existing;
    const command = await this.prisma.command.create({ data: {
      deviceId, type: dto.type,
      payload: (dto.payload ?? undefined) as Prisma.InputJsonValue | undefined,
      idempotencyKey, state: 'QUEUED',
      expiresAt: new Date(Date.now() + Math.max(this.ttlSeconds,
        dto.type === CommandType.SET_POLICY ? POLICY_COMMAND_TTL_SECONDS : this.ttlSeconds) * 1000),
    } });

    let sent = false;
    if (device.fcmToken) {
      const outcome = await this.fcm.sendData(device.fcmToken,
        { kind: 'command', commandId: command.id, type: command.type }, HIGH_PRIORITY.has(command.type));
      sent = outcome === 'sent';
      if (outcome === 'invalid-token') {
        await this.devices.clearRejectedFcmToken(device.id, device.fcmToken);
      }
    }
    const updated = await this.prisma.command.update({ where: { id: command.id }, data: { state: sent ? 'SENT' : 'QUEUED' } });
    await this.audit.log({ familyId, actor: 'parent', action: 'command_enqueued',
      data: { commandId: command.id, type: command.type, pushSent: sent } });
    return updated;
  }

  async listForFamilyDevice(familyId: string, deviceId: string): Promise<Command[]> {
    await this.devices.getForFamily(familyId, deviceId);
    return this.prisma.command.findMany({ where: { deviceId }, orderBy: { createdAt: 'desc' }, take: 100 });
  }

  async getForFamily(familyId: string, commandId: string): Promise<Command> {
    const command = await this.prisma.command.findFirst({ where: { id: commandId, device: { familyId } } });
    if (!command) throw new NotFoundException('command not found');
    return command;
  }

  async pullPending(deviceId: string): Promise<Command[]> {
    await this.expireStale(deviceId);
    return this.prisma.command.findMany({ where: { deviceId, state: { in: ['QUEUED', 'SENT'] } }, orderBy: { createdAt: 'asc' } });
  }

  async ack(deviceId: string, commandId: string): Promise<Command> {
    const command = await this.ownedCommand(deviceId, commandId);
    if (command.state === 'QUEUED' || command.state === 'SENT') return this.prisma.command.update({ where: { id: commandId }, data: { state: 'ACKED' } });
    return command;
  }

  async complete(deviceId: string, commandId: string, dto: CommandResultDto): Promise<Command> {
    const command = await this.ownedCommand(deviceId, commandId);
    if (command.state === 'DONE' || command.state === 'FAILED') return command;
    return this.prisma.command.update({ where: { id: commandId }, data: {
      state: dto.error ? 'FAILED' : 'DONE', result: (dto.result ?? undefined) as Prisma.InputJsonValue | undefined,
      error: dto.error ?? null,
    } });
  }

  private async expireStale(deviceId: string): Promise<void> {
    await this.prisma.command.updateMany({ where: { deviceId, state: { in: ['QUEUED', 'SENT'] }, expiresAt: { lt: new Date() } }, data: { state: 'EXPIRED' } });
  }

  private async ownedCommand(deviceId: string, commandId: string): Promise<Command> {
    const command = await this.prisma.command.findFirst({ where: { id: commandId, deviceId } });
    if (!command) throw new NotFoundException('command not found');
    return command;
  }
}
