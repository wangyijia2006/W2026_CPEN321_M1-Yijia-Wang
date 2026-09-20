import express, { type Express } from 'express';
import { createGoogleVerifier, type VerifyGoogleToken } from './auth';
import { env } from './config/env';

interface AppOptions {
  config?: typeof env;
  verifyToken?: VerifyGoogleToken;
  now?: () => Date;
}

export function formatServerTime(date: Date): string {
  const pad = (n: number): string => String(n).padStart(2, '0');
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const hours = pad(Math.floor(Math.abs(offset) / 60));
  const minutes = pad(Math.abs(offset) % 60);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())} GMT${sign}${hours}:${minutes}`;
}

export function createApp(options: AppOptions = {}): Express {
  const config = options.config ?? env;
  const verifyToken = options.verifyToken ?? createGoogleVerifier(config.googleClientId);
  const now = options.now ?? (() => new Date());
  const app = express();
  app.disable('x-powered-by');
  // nginx is on the same machine. Do not trust arbitrary internet proxies.
  app.set('trust proxy', 'loopback');

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use('/api', async (req, res, next) => {
    res.set('Cache-Control', 'no-store');
    if (!config.googleClientId) {
      res.status(503).json({ error: 'Google sign-in is not configured on the server.' });
      return;
    }
    const header = req.get('Authorization') ?? '';
    const match = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/i.exec(header);
    const token = match?.[1];
    if (!token || token.length > 16384) {
      res.status(401).json({ error: 'Please sign in with Google.' });
      return;
    }
    try {
      res.locals.user = await verifyToken(token);
    } catch {
      // Never log the ID token or include verification internals in a response.
      res.status(401).json({ error: 'Google sign-in could not be verified. Please sign in again.' });
      return;
    }
    next();
  });

  app.post('/api/auth/google', (_req, res) => {
    res.json({ user: res.locals.user });
  });

  // Three separate APIs required by M1. All use the same verified identity.
  app.get('/api/server/ip', (req, res) => {
    if (!config.serverPublicIp) {
      res.status(503).json({ error: 'Server public IP is not configured.' });
      return;
    }
    const clientIp = (req.ip ?? req.socket.remoteAddress ?? '').replace(/^::ffff:/, '');
    res.json({ serverIp: config.serverPublicIp, clientIp });
  });

  app.get('/api/server/time', (_req, res) => {
    res.json({ serverTime: formatServerTime(now()) });
  });

  app.get('/api/developer', (_req, res) => {
    if (!config.developerFirstName || !config.developerLastName) {
      res.status(503).json({ error: 'Developer name is not configured.' });
      return;
    }
    res.json({ firstName: config.developerFirstName, lastName: config.developerLastName });
  });

  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });
  return app;
}
