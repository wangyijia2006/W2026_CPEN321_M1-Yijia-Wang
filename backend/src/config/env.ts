import 'dotenv/config';
import { isIP } from 'node:net';

const rawPort = process.env.PORT?.trim();
const port = rawPort ? Number(rawPort) : 3000;
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('PORT must be an integer from 1 to 65535.');
}
const serverPublicIp = process.env.SERVER_PUBLIC_IP?.trim() ?? '';
if (serverPublicIp && isIP(serverPublicIp) === 0) {
  throw new Error('SERVER_PUBLIC_IP must contain an IPv4 or IPv6 address.');
}

export const env = {
  port,
  googleClientId: process.env.GOOGLE_CLIENT_ID?.trim() ?? '',
  serverPublicIp,
  developerFirstName: process.env.DEVELOPER_FIRST_NAME?.trim() ?? '',
  developerLastName: process.env.DEVELOPER_LAST_NAME?.trim() ?? '',
};
