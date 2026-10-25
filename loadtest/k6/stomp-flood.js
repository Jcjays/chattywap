// Abuse test, not a load test: one STOMP connection sends FRAMES messages of SIZE characters as fast as it can, without
// waiting for anything. Shows whether the server reads frames faster than it can process them (memory grows while
// they wait) or slows the client down instead: flood_send_duration is how long the client took to get every frame
// out. Watch the heap with sample-metrics.sh while it runs. Every frame is a stored message: load-test database only.
//
//   FRAMES     default 20000
//   SIZE       characters per message, at most 4000   default 4000
//   SUBSCRIBE  also subscribe to the conversation and count the messages that come back   default false. k6 can't
//              read while it is blocked writing, so with backpressure the server's deliveries to this client back up
//              and the server closes the connection after its send time limit, as it does for any client that doesn't
//              read.
//   WAIT       how long to keep the connection open for the deliveries   default 120s
import ws from 'k6/ws';
import { Counter, Trend } from 'k6/metrics';
import * as api from './lib/api.js';
import { nameUuid } from './lib/seed.js';

const FRAMES = parseInt(__ENV.FRAMES || '20000', 10);
const SIZE = Math.min(parseInt(__ENV.SIZE || '4000', 10), 4000);
const WAIT = parseInt((__ENV.WAIT || '120s').replace('s', ''), 10) * 1000;
const SUBSCRIBE = (__ENV.SUBSCRIBE || 'false') === 'true';
const WS_URL = __ENV.WS_URL || api.BASE_URL.replace(/^http/, 'ws') + '/ws';

const sendTime = new Trend('flood_send_duration', true);
const delivered = new Counter('flood_messages_delivered');
const allDelivered = new Trend('flood_all_delivered_after', true);

export const options = {
  scenarios: { flood: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '15m' } },
};

export function setup() {
  return { token: api.loginAll([1])[0] };
}

function frame(command, headers, body) {
  const lines = [command];
  Object.keys(headers).forEach((name) => lines.push(`${name}:${headers[name]}`));
  return `${lines.join('\n')}\n\n${body || ''}\u0000`;
}

export default function (data) {
  const conversationId = nameUuid('lt-direct-1-2');
  const content = JSON.stringify({ content: 'f'.repeat(SIZE) });
  let received = 0;
  let started = 0;
  ws.connect(WS_URL, {}, (socket) => {
    socket.on('open', () => socket.send(frame('CONNECT', { 'accept-version': '1.2', host: 'chattywap',
      'heart-beat': '0,0', Authorization: `Bearer ${data.token}` })));
    socket.on('message', (message) => {
      if (message.startsWith('CONNECTED')) {
        if (SUBSCRIBE) {
          socket.send(frame('SUBSCRIBE', { id: 'sub-0',
            destination: `/topic/conversations/${conversationId}/messages` }));
        }
        started = Date.now();
        for (let i = 0; i < FRAMES; i++) {
          socket.send(frame('SEND', { destination: `/app/conversations/${conversationId}/messages`,
            'content-type': 'application/json' }, content));
        }
        sendTime.add(Date.now() - started);
        if (!SUBSCRIBE) {
          socket.close();
        }
      }
      else if (message.startsWith('MESSAGE')) {
        received++;
        delivered.add(1);
        if (received === FRAMES) {
          allDelivered.add(Date.now() - started);
          socket.close();
        }
      }
    });
    socket.setTimeout(() => socket.close(), WAIT);
  });
}
