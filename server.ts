/**
 * CarbonFlow — Express + Vite Enterprise Full-Stack Server
 * Binds to 0.0.0.0:3000
 */
import express from 'express';
import path from 'path';
import { createServer as createViteServer } from 'vite';
import { apiRouter } from './server/routes.ts';
import { assertProductionConfiguration } from './server/config.ts';
import { corsPolicy, createRateLimiter, safeErrorHandler, securityHeaders } from './server/http-security.ts';

assertProductionConfiguration();

async function startServer() {
  const app = express();
  const PORT = Number(process.env.PORT || 3000);
  const jsonLimit = process.env.JSON_BODY_LIMIT || '1mb';

  app.disable('x-powered-by');
  app.set('trust proxy', process.env.TRUST_PROXY === 'true');
  app.use(securityHeaders);
  app.use(corsPolicy);
  app.use(createRateLimiter());

  // Keep API body parsing bounded; multipart evidence uploads use their own limits.
  app.use(express.json({ limit: jsonLimit }));
  app.use(express.urlencoded({ extended: true, limit: jsonLimit }));

  // Health check endpoint
  app.get('/api/health', (req, res) => {
    res.json({
      status: 'UP',
      service: 'CarbonFlow GHG Accounting Engine',
      version: '1.0.0-PRO',
      timestamp: new Date().toISOString(),
    });
  });

  // Mount API Router under /api/v1
  app.use('/api/v1', apiRouter);
  app.use(safeErrorHandler);

  // Vite middleware for development vs Static serving for production
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[CarbonFlow] Server running on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('[CarbonFlow] Failed to start server:', err);
  process.exit(1);
});
