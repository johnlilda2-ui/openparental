export function buildConfiguration(env: NodeJS.ProcessEnv = process.env) {
  const nodeEnv = env.NODE_ENV ?? 'development';
  const configuredJwtSecret = env.JWT_SECRET;

  if (
    nodeEnv === 'production' &&
    (!configuredJwtSecret || configuredJwtSecret.length < 32)
  ) {
    throw new Error(
      'JWT_SECRET must be configured with at least 32 characters in production; refusing to start with an insecure default.',
    );
  }

  return {
    port: parseInt(env.PORT ?? '3000', 10),
    nodeEnv,
    corsOrigins:
      env.CORS_ORIGINS ??
      (nodeEnv === 'production'
        ? ''
        : 'http://localhost:3000,http://localhost:5173'),
    databaseUrl: env.DATABASE_URL,
    redisUrl: env.REDIS_URL ?? 'redis://localhost:6379',
    jwt: {
      secret: configuredJwtSecret ?? 'dev-secret-change-me',
      accessTtl: parseInt(env.JWT_ACCESS_TTL ?? '900', 10),
      refreshTtl: parseInt(env.JWT_REFRESH_TTL ?? '2592000', 10),
      deviceTtl: parseInt(env.JWT_DEVICE_TTL ?? '31536000', 10),
    },
    heartbeat: {
      intervalSeconds: parseInt(env.HEARTBEAT_INTERVAL_SECONDS ?? '75', 10),
      missGrace: parseInt(env.HEARTBEAT_MISS_GRACE ?? '2', 10),
    },
    command: {
      ttlSeconds: parseInt(env.COMMAND_TTL_SECONDS ?? '300', 10),
    },
    fcm: {
      serviceAccountPath: env.FCM_SERVICE_ACCOUNT_PATH ?? '',
    },
  };
}

export default buildConfiguration;
