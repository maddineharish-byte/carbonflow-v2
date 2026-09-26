import express from 'express';
import bcrypt from 'bcryptjs';
import { apiRouter } from './routes.ts';
import { db } from './db.ts';
import type { OrganizationMembership, RoleName, User } from './types.ts';

const email = process.env.TEST_USER_EMAIL;
const password = process.env.TEST_USER_PASSWORD;
const userId = process.env.TEST_USER_ID;
const organizationId = process.env.TEST_ORGANIZATION_ID;
const role = process.env.TEST_ROLE as RoleName;

if (!email || !password || !userId || !organizationId || !role) {
  throw new Error('Integration test backend configuration is incomplete.');
}

const user: User = {
  id: userId,
  email,
  passwordHash: bcrypt.hashSync(password, 4),
  fullName: 'TASK-004A Integration Test User',
  isActive: true,
  createdAt: new Date().toISOString(),
};
const membership: OrganizationMembership = {
  id: `integration-membership-${userId}`,
  organizationId,
  userId,
  role,
  isActive: true,
  createdAt: new Date().toISOString(),
};
db.users.push(user);
db.memberships.push(membership);

const app = express();
app.use(express.json({ limit: '1mb' }));
app.use('/api/v1', apiRouter);

const server = app.listen(0, '127.0.0.1', () => {
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Unable to determine integration test port.');
  process.stdout.write(`READY:${address.port}\n`);
});

function shutdown() {
  server.close(() => process.exit(0));
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
