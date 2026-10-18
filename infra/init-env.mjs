#!/usr/bin/env node
/**
 * Creates the repo-root .env from .env.example, replacing every "change-me" placeholder with a
 * random value of the right format (WSO2_ENCRYPTION_KEY: 32 hex characters; passwords: letters and
 * digits only, within WSO2's 5-30 character rule).
 *
 *   node infra/init-env.mjs
 *
 * If .env already exists, only keys that are missing from it are added (e.g. after an upgrade that
 * introduced new settings). Existing values are never changed: the databases and WSO2 keep the
 * passwords they were first created with, so changing them later breaks logins.
 */
import { randomBytes } from 'node:crypto';
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const target = join(root, '.env');

const alphanumeric = (length) => {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  return [...randomBytes(length)].map((b) => chars[b % chars.length]).join('');
};
const secretFor = (key) => (key === 'WSO2_ENCRYPTION_KEY' ? randomBytes(16).toString('hex') : alphanumeric(20));
const keyOf = (line) => /^([A-Z0-9_]+)=/.exec(line)?.[1];

const example = readFileSync(join(root, '.env.example'), 'utf8').split(/\r?\n/);
const fill = (line) => {
  const m = /^([A-Z0-9_]+)=change-me.*$/.exec(line);
  return m ? `${m[1]}=${secretFor(m[1])}` : line;
};

if (!existsSync(target)) {
  writeFileSync(target, example.map(fill).join('\n'));
  console.log('Created .env with generated secrets. It is git-ignored; keep it.');
  process.exit(0);
}

const present = new Set(readFileSync(target, 'utf8').split(/\r?\n/).map(keyOf).filter(Boolean));
const missing = example.filter((line) => keyOf(line) && !present.has(keyOf(line))).map(fill);
if (missing.length === 0) {
  console.log('.env already has every setting; left unchanged.');
} else {
  appendFileSync(target, `\n# Added by init-env.mjs\n${missing.join('\n')}\n`);
  console.log(`.env: added ${missing.map(keyOf).join(', ')} (existing values unchanged).`);
}
