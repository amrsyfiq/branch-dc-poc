#!/usr/bin/env node
/**
 * Creates the repo-root .env from .env.example, replacing every "change-me-..." placeholder with a
 * random value of the right format (WSO2_ENCRYPTION_KEY: 32 hex characters; passwords: letters and
 * digits only, within WSO2's 5-30 character rule).
 *
 *   node infra/init-env.mjs
 *
 * Never overwrites an existing .env: the Oracle and WSO2 containers keep the passwords they were
 * first created with, so changing them later breaks logins (see README troubleshooting).
 */
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const target = join(root, '.env');

if (existsSync(target)) {
  console.log('.env already exists; left unchanged.');
  process.exit(0);
}

const alphanumeric = (length) => {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  return [...randomBytes(length)].map((b) => chars[b % chars.length]).join('');
};

let replaced = 0;
const lines = readFileSync(join(root, '.env.example'), 'utf8').split(/\r?\n/).map((line) => {
  const m = /^([A-Z0-9_]+)=change-me.*$/.exec(line);
  if (!m) return line;
  replaced++;
  const value = m[1] === 'WSO2_ENCRYPTION_KEY' ? randomBytes(16).toString('hex') : alphanumeric(20);
  return `${m[1]}=${value}`;
});

writeFileSync(target, lines.join('\n'));
console.log(`Created .env with ${replaced} generated secrets. It is git-ignored; keep it.`);
