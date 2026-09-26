import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { LocalStorageAdapter, assertFileSignature } from './storage.ts';

test('evidence storage rejects content that does not match declared type', () => {
  assert.throws(() => assertFileSignature('application/pdf', Buffer.from('not a pdf')), /does not match/);
  assert.throws(() => assertFileSignature('image/png', Buffer.from('not a png')), /does not match/);
});

test('evidence storage writes tenant-scoped files and cleans them up', async () => {
  const storage = new LocalStorageAdapter();
  const organizationId = `storage-test-${randomUUID()}`;
  const stored = await storage.saveFile(organizationId, 'evidence.pdf', 'application/pdf', Buffer.from('%PDF-1.7 test'));
  assert.equal(stored.fileSizeBytes, 13);
  assert.match(stored.sha256Hash, /^[a-f0-9]{64}$/);
  assert.equal((await storage.readFile(stored.storagePath)).toString(), '%PDF-1.7 test');
  await storage.deleteFile(stored.storagePath);
  await assert.rejects(() => storage.readFile(stored.storagePath), /not found/);
});

test('concurrent evidence uploads produce distinct tenant-scoped files', async () => {
  const storage = new LocalStorageAdapter();
  const organizationId = `storage-concurrent-${randomUUID()}`;
  const stored = await Promise.all(Array.from({ length: 5 }, (_, index) =>
    storage.saveFile(organizationId, `evidence-${index}.pdf`, 'application/pdf', Buffer.from(`%PDF-1.7 ${index}`)),
  ));
  assert.equal(new Set(stored.map((item) => item.storagePath)).size, 5);
  await Promise.all(stored.map((item) => storage.deleteFile(item.storagePath)));
});

test('evidence storage rejects paths outside its base directory', async () => {
  const storage = new LocalStorageAdapter();
  await assert.rejects(() => storage.readFile(`${(storage as any).baseDir}-other/file.pdf`), /path traversal/);
});
