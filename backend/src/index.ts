import { createServer } from 'node:http';
import { Server } from 'socket.io';
import { createApp } from './app';
import { env } from './config/env';

const courseUrl = process.env.COURSE_WS_URL ?? 'wss://8.229.22.124';
const parsedUrl = new URL(courseUrl);
if (parsedUrl.protocol !== 'wss:' &&
    !(parsedUrl.protocol === 'ws:' && ['localhost', '127.0.0.1'].includes(parsedUrl.hostname))) {
  throw new Error('COURSE_WS_URL must use wss:// (ws:// is only allowed for local tests).');
}

const server = createServer(createApp());
const io = new Server(server, {
  transports: ['websocket'],
  maxHttpBufferSize: 16 * 1024,
});

// Each phone gets its own upstream connection; closing the screen releases it.
io.on('connection', (phone) => {
  let upstream: WebSocket | undefined;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let connectTimer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  let retryDelay = 1000;

  function connectCourse(): void {
    if (stopped) return;
    phone.emit('stream-status', 'Connecting to course server...');
    const connection = new WebSocket(courseUrl);
    upstream = connection;
    connectTimer = setTimeout(() => connection.close(), 15000);

    connection.addEventListener('open', () => {
      clearTimeout(connectTimer);
      if (stopped) { connection.close(); return; }
      retryDelay = 1000;
      phone.emit('stream-reset');
      phone.emit('stream-status', 'Connected. Waiting for pixels...');
    });

    connection.addEventListener('message', (event) => {
      if (stopped || !phone.connected) return;
      // Keep the course's JSON string exactly as received: no parse/stringify,
      // no pixel batching, and no application-level delay.
      if (typeof event.data === 'string') {
        phone.emit('pixel', event.data);
      } else {
        phone.emit('stream-status', 'Unexpected binary data from course server.');
      }
    });

    connection.addEventListener('error', () => {
      console.error('Course WebSocket connection failed. Check network and certificate trust.');
      if (!stopped) phone.emit('stream-status', 'Course connection failed; retrying...');
      connection.close();
    });

    connection.addEventListener('close', () => {
      clearTimeout(connectTimer);
      if (stopped) return;
      phone.emit('stream-status', 'Course connection closed; reconnecting...');
      reconnectTimer = setTimeout(connectCourse, retryDelay);
      retryDelay = Math.min(retryDelay * 2, 10000);
    });
  }

  phone.once('disconnect', () => {
    stopped = true;
    clearTimeout(reconnectTimer);
    clearTimeout(connectTimer);
    upstream?.close();
  });
  connectCourse();
});

server.listen(env.port, () => {
  console.log(`Server listening on port ${env.port}`);
  console.log('Live pixel relay enabled (Socket.IO over WebSocket).');
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.once(signal, () => {
    io.close(() => process.exit(0));
  });
}
