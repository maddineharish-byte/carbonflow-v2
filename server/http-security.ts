import type { NextFunction, Request, Response } from 'express';

export interface RateLimiterOptions {
  windowMs?: number;
  maxRequests?: number;
  now?: () => number;
}

export function securityHeaders(_req: Request, res: Response, next: NextFunction) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  if (process.env.NODE_ENV === 'production') {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  }
  next();
}

export function corsPolicy(req: Request, res: Response, next: NextFunction) {
  const configured = (process.env.CORS_ORIGINS || '').split(',').map((value) => value.trim()).filter(Boolean);
  const origin = req.headers.origin;
  if (origin && configured.includes(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Credentials', 'true');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
  }
  if (req.method === 'OPTIONS') {
    res.status(204).end();
    return;
  }
  next();
}

export function safeErrorHandler(error: any, _req: Request, res: Response, next: NextFunction) {
  if (res.headersSent) return next(error);
  if (error?.type === 'entity.too.large') {
    return res.status(413).json({ success: false, error: { code: 'PAYLOAD_TOO_LARGE', message: 'Request body is too large.' } });
  }
  if (error instanceof SyntaxError && 'body' in error) {
    return res.status(400).json({ success: false, error: { code: 'INVALID_JSON', message: 'Request body must be valid JSON.' } });
  }
  return res.status(500).json({ success: false, error: { code: 'INTERNAL_ERROR', message: 'An internal server error occurred.' } });
}

export function createRateLimiter(options: RateLimiterOptions = {}) {
  const windowMs = options.windowMs ?? 60_000;
  const maxRequests = options.maxRequests ?? 120;
  const now = options.now ?? Date.now;
  const buckets = new Map<string, { startedAt: number; count: number }>();

  return (req: Request, res: Response, next: NextFunction) => {
    const key = req.ip || req.socket.remoteAddress || 'unknown';
    const timestamp = now();
    const current = buckets.get(key);
    const bucket = !current || timestamp - current.startedAt >= windowMs
      ? { startedAt: timestamp, count: 0 }
      : current;
    bucket.count += 1;
    buckets.set(key, bucket);
    res.setHeader('RateLimit-Limit', String(maxRequests));
    res.setHeader('RateLimit-Remaining', String(Math.max(0, maxRequests - bucket.count)));
    if (bucket.count > maxRequests) {
      res.setHeader('Retry-After', String(Math.ceil((windowMs - (timestamp - bucket.startedAt)) / 1000)));
      res.status(429).json({ success: false, error: { code: 'RATE_LIMITED', message: 'Too many requests.' } });
      return;
    }
    next();
  };
}
