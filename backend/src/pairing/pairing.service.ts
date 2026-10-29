import {
  BadRequestException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { AuditService } from '../audit/audit.service';
import { pairingCode, randomToken, sha256 } from '../common/hash.util';
import { PrismaService } from '../prisma/prisma.service';
import { TokenService } from '../auth/token.service';
import { ClaimPairingDto } from './dto/claim-pairing.dto';
import { CreatePairingDto } from './dto/create-pairing.dto';

const PAIRING_TTL_MS = 15 * 60 * 1000; // 15 minutes

export interface CreatedPairing {
  id: string;
  code: string;
  qrToken: string;
  expiresAt: Date;
}

export interface ClaimResult {
  deviceId: string;
  deviceToken: string;
  deviceSecret: string;
}

@Injectable()
export class PairingService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly tokens: TokenService,
    private readonly audit: AuditService,
  ) {}

  /** Parent creates a pairing slot; returns the code + QR token to display. */
  async create(familyId: string, dto: CreatePairingDto): Promise<CreatedPairing> {
    const pairing = await this.prisma.pairing.create({
      data: {
        familyId,
        code: pairingCode(),
        qrToken: randomToken(),
        deviceName: dto.deviceName,
        status: 'PENDING',
        expiresAt: new Date(Date.now() + PAIRING_TTL_MS),
      },
    });
    return {
      id: pairing.id,
      code: pairing.code,
      qrToken: pairing.qrToken,
      expiresAt: pairing.expiresAt,
    };
  }

  /**
   * Claim the pairing and create its device in one database transaction.
   * The conditional status update is the concurrency gate: only one claimant
   * can transition a still-pending, unexpired pairing to CLAIMED.
   */
  async claim(dto: ClaimPairingDto): Promise<ClaimResult> {
    const pairing = await this.prisma.pairing.findFirst({
      where: { OR: [{ qrToken: dto.token }, { code: dto.token }] },
    });
    if (!pairing) throw new NotFoundException('pairing not found');
    if (pairing.status !== 'PENDING') {
      throw new BadRequestException('pairing already used');
    }

    const now = new Date();
    if (pairing.expiresAt.getTime() < now.getTime()) {
      await this.prisma.pairing.updateMany({
        where: { id: pairing.id, status: 'PENDING' },
        data: { status: 'EXPIRED' },
      });
      throw new BadRequestException('pairing expired');
    }

    const deviceSecret = randomToken();
    const result = await this.prisma.$transaction(async (tx) => {
      const claimed = await tx.pairing.updateMany({
        where: {
          id: pairing.id,
          status: 'PENDING',
          expiresAt: { gt: now },
        },
        data: { status: 'CLAIMED' },
      });

      if (claimed.count !== 1) {
        throw new BadRequestException('pairing already used or expired');
      }

      const device = await tx.device.create({
        data: {
          familyId: pairing.familyId,
          name: dto.deviceName ?? pairing.deviceName ?? dto.model ?? 'Child device',
          manufacturer: dto.manufacturer,
          model: dto.model,
          osVersion: dto.osVersion,
          appVersion: dto.appVersion,
          fcmToken: dto.fcmToken,
          secretHash: sha256(deviceSecret),
          status: 'ONLINE',
          lastSeenAt: now,
        },
      });

      await tx.pairing.update({
        where: { id: pairing.id },
        data: { claimedDeviceId: device.id },
      });

      return device;
    });

    const deviceToken = await this.tokens.signDevice(result.id, pairing.familyId);
    await this.audit.log({
      familyId: pairing.familyId,
      actor: result.id,
      action: 'device_paired',
      data: { pairingId: pairing.id, model: result.model ?? undefined },
    });

    return { deviceId: result.id, deviceToken, deviceSecret };
  }

  /** Parent polls pairing status while the child sets up the device. */
  async getStatus(familyId: string, id: string) {
    const pairing = await this.prisma.pairing.findFirst({
      where: { id, familyId },
    });
    if (!pairing) throw new NotFoundException('pairing not found');
    return {
      id: pairing.id,
      status: pairing.status,
      claimedDeviceId: pairing.claimedDeviceId,
      expiresAt: pairing.expiresAt,
    };
  }
}
