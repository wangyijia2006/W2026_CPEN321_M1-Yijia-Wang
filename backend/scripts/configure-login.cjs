// Run from your own backend checkout after reviewing .env.example.
// Preserve unrelated settings and back up an existing .env before changing it.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const target = path.join(root, '.env');
const expected = ['PORT', 'GOOGLE_CLIENT_ID', 'SERVER_PUBLIC_IP', 'DEVELOPER_FIRST_NAME', 'DEVELOPER_LAST_NAME'];
const values = new Map();
for (const line of fs.readFileSync(path.join(root, '.env.example'), 'utf8').split(/\r?\n/)) {
  const match = /^([A-Z_]+)=(.*)$/.exec(line);
  if (match && expected.includes(match[1])) values.set(match[1], match[2]);
}
if (values.size !== expected.length || [...values.values()].some((value) => !value.trim())) {
  throw new Error('The five login settings must be present in .env.example.');
}
const original = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : '';
if (fs.existsSync(target)) {
  const suffix = new Date().toISOString().replace(/[:.]/g, '-');
  fs.copyFileSync(target, path.join(root, '.env.before-login-' + suffix), fs.constants.COPYFILE_EXCL);
}
const kept = original.split(/\r?\n/).filter((line) => {
  const match = /^\s*(?:export\s+)?([A-Z_]+)\s*=/.exec(line);
  return !match || !values.has(match[1]);
});
const updated = kept.join('\n').trimEnd() + '\n' + [...values].map(([key, value]) => `${key}=${value}`).join('\n') + '\n';
fs.writeFileSync(target, updated.trimStart(), { mode: 0o600 });
console.log('Login settings saved to .env. Existing unrelated settings were preserved.');
