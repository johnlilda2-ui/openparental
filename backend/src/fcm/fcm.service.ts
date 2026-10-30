import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as admin from 'firebase-admin';
import { readFileSync } from 'fs';

export type FcmSendResult = 'sent' | 'disabled' | 'invalid-token' | 'failed';

@Injectable()
export class FcmService implements OnModuleInit {
  private readonly logger = new Logger(FcmService.name);
  private app: admin.app.App | null = null;

  constructor(private readonly config: ConfigService) {}

  onModuleInit(): void {
    const path = this.config.get<string>('fcm.serviceAccountPath');
    const json = this.config.get<string>('fcm.serviceAccountJson');
    if (!path && !json) {
      this.logger.warn('FCM not configured — push delivery is disabled.');
      return;
    }
    try {
      const serviceAccount = JSON.parse(json || readFileSync(path as string, 'utf8'));
      this.app = admin.initializeApp({ credential: admin.credential.cert(serviceAccount) }, 'monii');
      this.logger.log('FCM initialized.');
    } catch (error) {
      this.logger.error(`FCM initialization failed: ${String(error)}`);
    }
  }

  get enabled(): boolean { return this.app !== null; }

  async sendData(token: string, data: Record<string, string>, highPriority = true): Promise<FcmSendResult> {
    if (!this.app) return 'disabled';
    try {
      await admin.messaging(this.app).send({ token, data, android: { priority: highPriority ? 'high' : 'normal' } });
      return 'sent';
    } catch (error) {
      const code = (error as { code?: string })?.code;
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token') {
        this.logger.warn(`FCM rejected an obsolete registration token (${code}); it must be refreshed by the child app.`);
        return 'invalid-token';
      }
      this.logger.error(`FCM send failed (${code ?? 'unknown'}): ${String(error)}`);
      return 'failed';
    }
  }
}
