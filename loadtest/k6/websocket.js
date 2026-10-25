// STOMP over WebSocket: many connected members, messages sent through REST, delivery measured at every subscriber.
//
// Users 1..CONNECTIONS connect (one connection each) and subscribe to the messages topic of their direct conversations
// and, for users 1-100, the hot group. Meanwhile users send through REST at SEND_RATE per second: half to the hot group
// (delivered to up to 100 connections), half to a direct conversation between two connected users. Every message
// carries its send time, so each subscriber measures the time from the send request to the delivery.
//
// With several application instances, connections and sends are spread over them, and deliveries of messages sent
// through another instance than the subscriber's (passed on through RabbitMQ) are also measured on their own.
//
//   CONNECTIONS  default 200
//   SEND_RATE    messages per second   default 10
//   DURATION     how long the senders send; connections stay open 10 seconds longer   default 1m
//   BASE_URLS    comma-separated base URLs of several instances, as seen from the k6 container; connection n and the
//                sends of user n go to instance n mod count   default BASE_URL alone
import ws from 'k6/ws';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import * as api from './lib/api.js';
import { HOT_GROUP_ID, HOT_GROUP_MEMBERS, directsOf, nameUuid, SEED_USERS } from './lib/seed.js';

const CONNECTIONS = parseInt(__ENV.CONNECTIONS || '200', 10);
const SEND_RATE = parseInt(__ENV.SEND_RATE || '10', 10);
const DURATION = __ENV.DURATION || '1m';
const BASE_URLS = (__ENV.BASE_URLS || api.BASE_URL).split(',');
const WS_URLS = BASE_URLS.map((url) => url.replace(/^http/, 'ws') + '/ws');

/** The instance user n connects and sends to. */
function instanceOf(n) {
  return n % BASE_URLS.length;
}

const deliveryLatency = new Trend('stomp_delivery_latency', true);
// With several instances: deliveries of messages sent through the subscriber's own instance, and through another one,
// which cross RabbitMQ.
const sameInstanceLatency = new Trend('stomp_delivery_latency_same_instance', true);
const otherInstanceLatency = new Trend('stomp_delivery_latency_other_instance', true);
const delivered = new Counter('stomp_messages_delivered');
// Deliveries the sends should cause: one per connected member of the conversation, the sender included.
const expected = new Counter('stomp_messages_expected');
const connected = new Rate('stomp_connected');
const errors = new Counter('stomp_error_frames');

function parseDuration(text) {
  const match = /^(\d+)(ms|s|m)$/.exec(text);
  const factor = { ms: 1, s: 1000, m: 60000 }[match[2]];
  return parseInt(match[1], 10) * factor;
}

export const options = {
  scenarios: {
    subscribers: {
      executor: 'per-vu-iterations',
      vus: CONNECTIONS,
      iterations: 1,
      maxDuration: `${parseDuration(DURATION) / 1000 + 60}s`,
      exec: 'subscriber',
    },
    senders: {
      executor: 'constant-arrival-rate',
      rate: SEND_RATE,
      timeUnit: '1s',
      // Starts once every connection had time to subscribe.
      startTime: '5s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'sender',
    },
  },
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    stomp_connected: ['rate>0.99'],
    stomp_delivery_latency: ['p(95)<1000'],
  },
};

export function setup() {
  const numbers = [];
  for (let n = 1; n <= CONNECTIONS; n++) {
    numbers.push(n);
  }
  return { tokens: api.loginAll(numbers) };
}

function frame(command, headers, body) {
  const lines = [command];
  Object.keys(headers).forEach((name) => lines.push(`${name}:${headers[name]}`));
  return `${lines.join('\n')}\n\n${body || ''}\u0000`;
}

function parseFrames(data) {
  return data.split('\u0000').filter((text) => text.trim().length > 0).map((text) => {
    const separator = text.indexOf('\n\n');
    const head = text.substring(0, separator).replace(/^\n+/, '').split('\n');
    return { command: head[0], body: text.substring(separator + 2) };
  });
}

export function subscriber(data) {
  // One iteration per connection, numbered from 0 within the scenario (VU numbers are shared with the senders).
  const n = exec.scenario.iterationInTest + 1;
  const token = data.tokens[n - 1];
  // Only conversations whose other member is connected too: the senders post to those.
  const topics = directsOf(n).filter((id, i) => i < 10).map((id) => `/topic/conversations/${id}/messages`);
  if (n <= HOT_GROUP_MEMBERS) {
    topics.push(`/topic/conversations/${HOT_GROUP_ID}/messages`);
  }
  const lifetime = parseDuration(DURATION) + 15000;

  const instance = instanceOf(n);

  const res = ws.connect(WS_URLS[instance], {}, (socket) => {
    socket.on('open', () => {
      socket.send(frame('CONNECT', { 'accept-version': '1.2', host: 'chattywap', 'heart-beat': '0,0',
        Authorization: `Bearer ${token}` }));
    });
    socket.on('message', (message) => {
      parseFrames(message).forEach((received) => {
        if (received.command === 'CONNECTED') {
          connected.add(true);
          topics.forEach((topic, i) => socket.send(frame('SUBSCRIBE', { id: `sub-${i}`, destination: topic })));
        }
        else if (received.command === 'MESSAGE') {
          const sentAt = /sent=(\d+) via=(\d+)/.exec(received.body);
          if (sentAt) {
            const latency = Date.now() - parseInt(sentAt[1], 10);
            deliveryLatency.add(latency);
            if (BASE_URLS.length > 1) {
              (parseInt(sentAt[2], 10) === instance ? sameInstanceLatency : otherInstanceLatency).add(latency);
            }
            delivered.add(1);
          }
        }
        else if (received.command === 'ERROR') {
          errors.add(1);
        }
      });
    });
    socket.on('error', () => connected.add(false));
    socket.setTimeout(() => socket.close(), lifetime);
  });
  check(res, { 'handshake 101': (r) => r && r.status === 101 });
}

export function sender(data) {
  const n = Math.floor(Math.random() * Math.min(CONNECTIONS, HOT_GROUP_MEMBERS)) + 1;
  const instance = instanceOf(n);
  const content = () => `sent=${Date.now()} via=${instance}`;
  if (Math.random() < 0.5) {
    const res = api.send(data.tokens[n - 1], HOT_GROUP_ID, content(), 'messages:send-hot', BASE_URLS[instance]);
    if (res.status === 201) {
      expected.add(Math.min(CONNECTIONS, HOT_GROUP_MEMBERS));
    }
  }
  else {
    // A conversation with a user after n; both are connected when n + 5 <= CONNECTIONS.
    const other = Math.min(n + 1 + Math.floor(Math.random() * 5), SEED_USERS);
    const res = api.send(data.tokens[n - 1], nameUuid(`lt-direct-${n}-${other}`), content(),
      'messages:send-direct', BASE_URLS[instance]);
    if (res.status === 201) {
      expected.add(other <= CONNECTIONS ? 2 : 1);
    }
  }
}
