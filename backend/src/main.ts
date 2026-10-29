import 'reflect-metadata';
import { ValidationPipe } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule);
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  const config = app.get(ConfigService);
  const configuredOrigins = (config.get<string>('corsOrigins') ?? '')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);
  // Native clients without an Origin header remain supported. Browser origins
  // must be explicitly allowlisted in CORS_ORIGINS for production deployments.
  app.enableCors({
    origin: configuredOrigins.length ? configuredOrigins : false,
  });
  const port = config.get<number>('port') ?? 3000;
  await app.listen(port);
  // eslint-disable-next-line no-console
  console.log(`monii-backend listening on :${port}`);
}

void bootstrap();
