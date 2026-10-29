import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as admin from 'firebase-admin';
import { readFileSync } from 'fs';

/**
 * Push abstraction over firebase-admin. Supports either a mounted service-account
 * file or JSON stored in the hosting provider's secret environment variables.
 * With no credentials it remains in explicit stub mode so local development works.
 *
 * High priority is reserved for wake-critical messages (lock, screen-view). FCM
 * quota-limits high priority; abuse silently downgrades to Doze-buffered normal.
 */
@Injectable()
export class FcmService implements OnModuleInit {
  private readonly logger = new Logger(FcmService.name);
  private app: admin.app.App | null = null;

  constructor(private readonly config: ConfigService) {}

  onModuleInit(): void {
    const path = this.config.get<string>('fcm.serviceAccountPath');
    const json = this.config.get<string>('fcm.serviceAccountJson');

    if (!path && !json) {
      this.logger.warn(
        'FCM not configured — stub mode (pushes logged, not sent). Set FCM_SERVICE_ACCOUNT_JSON or FCM_SERVICE_ACCOUNT_PATH to enable push delivery.',
      );
      return;
    }

    try {
      const serviceAccount = JSON.parse(
        json || readFileSync(path as string, 'utf8'),
      );
      this.app = admin.initializeApp(
        { credential: admin.credential.cert(serviceAccount) },
        'monii',
      );
      this.logger.log('FCM initialized.');
    } catch {
      this.logger.error(
        'FCM initialization failed — push delivery is disabled. Verify the Firebase service-account JSON/path.',
      );
    }
  }

  get enabled(): boolean {
    return this.app !== null;
  }

  /** Returns true if the message was handed to FCM, false in stub/failure. */
  async sendData(
    token: string,
    data: Record<string, string>,
    highPriority = true,
  ): Promise<boolean> {
    if (!this.app) {
      this.logger.debug(
        `[stub] FCM -> ${token.slice(0, 8)}… ${JSON.stringify(data)}`,
      );
      return false;
    }
    try {
      await admin.messaging(this.app).send({
        token,
        data,
        android: { priority: highPriority ? 'high' : 'normal' },
      });
      return true;
    } catch (e) {
      this.logger.warn(`FCM send failed: ${String(e)}`);
      return false;
    }
  }
}
