import {
  BadRequestException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { AuditService } from '../audit/audit.service';
import { pairingCode, randomToken } from '../common/hash.util';
import { PrismaService } from '../prisma/prisma.service';
import { TokenService } from '../auth/token.service';
import { DevicesService } from '../devices/devices.service';
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
    private readonly devices: DevicesService,
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
   * Claims a pairing in one transaction. The conditional PENDING -> CLAIMED
   * update is the race guard: concurrent devices cannot both enroll against the
   * same code. Device creation and assignment roll back if token minting fails.
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
        where: { id: pairing.id, status: 'PENDING', expiresAt: { lt: now } },
        data: { status: 'EXPIRED' },
      });
      throw new BadRequestException('pairing expired');
    }

    const claimed = await this.prisma.$transaction(async (tx) => {
      const reservation = await tx.pairing.updateMany({
        where: {
          id: pairing.id,
          status: 'PENDING',
          expiresAt: { gt: new Date() },
        },
        data: { status: 'CLAIMED' },
      });
      if (reservation.count !== 1) {
        throw new BadRequestException(
          'pairing was already claimed or has expired',
        );
      }

      const { device, deviceSecret } = await this.devices.createForPairing(
        pairing.familyId,
        {
          ...dto,
          deviceName: dto.deviceName ?? pairing.deviceName ?? undefined,
        },
        tx,
      );
      const deviceToken = await this.tokens.signDevice(device.id, pairing.familyId);
      await tx.pairing.update({
        where: { id: pairing.id },
        data: { claimedDeviceId: device.id },
      });
      return { device, deviceToken, deviceSecret };
    });

    // Pairing has already committed; telemetry failure must not strand the
    // successful child enrollment without returning its one-time device secret.
    await this.audit.log({
      familyId: pairing.familyId,
      actor: claimed.device.id,
      action: 'device_paired',
      data: { pairingId: pairing.id, model: claimed.device.model ?? undefined },
    }).catch(() => undefined);

    return {
      deviceId: claimed.device.id,
      deviceToken: claimed.deviceToken,
      deviceSecret: claimed.deviceSecret,
    };
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
