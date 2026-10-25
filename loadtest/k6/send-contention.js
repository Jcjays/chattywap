// Concurrent sends to one conversation compared with sends spread over many, with unrelated reads running alongside.
// Sends to one conversation are serialized by its lock (ARCHITECTURE.md, decision 12), and each waiting send holds a
// database connection, so the readers show whether a busy conversation slows down everybody else.
//
//   MODE         same: every sender posts to the hot group (users 1-100 are its members)
//                spread: each sender posts to a direct conversation of its own
//   SENDERS      concurrent senders, each sending again as soon as its last send returned   default 50 (at most 100)
//   READ_RATE    unrelated history reads per second, by users 101-200 in their own conversations   default 20
//   DURATION     default 1m
import { SharedArray } from 'k6/data';
import * as api from './lib/api.js';
import { directsOf, HOT_GROUP_ID, nameUuid } from './lib/seed.js';

const MODE = __ENV.MODE || 'same';
const SENDERS = Math.min(parseInt(__ENV.SENDERS || '50', 10), 100);
const READERS = 100;
const DURATION = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    senders: {
      executor: 'constant-vus',
      vus: SENDERS,
      duration: DURATION,
      exec: 'sender',
    },
    readers: {
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.READ_RATE || '20', 10),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'reader',
    },
  },
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
    'http_req_duration{name:messages:send}': ['p(95)<1000'],
    'http_req_duration{name:messages:history}': ['p(95)<500'],
  },
};

const readerConversations = new SharedArray('readerConversations', () => {
  const all = [];
  for (let n = SENDERS + 1; n <= SENDERS + READERS; n++) {
    all.push(directsOf(n));
  }
  return all;
});

export function setup() {
  const numbers = [];
  for (let n = 1; n <= SENDERS + READERS; n++) {
    numbers.push(n);
  }
  return { tokens: api.loginAll(numbers) };
}

export function sender(data) {
  const n = __VU % SENDERS + 1;
  // spread: user n's conversation with user n + 1, which no other sender uses.
  const conversationId = MODE === 'same' ? HOT_GROUP_ID : nameUuid(`lt-direct-${n}-${n + 1}`);
  api.send(data.tokens[n - 1], conversationId, `contention ${MODE} ${Date.now()}`);
}

export function reader(data) {
  const index = Math.floor(Math.random() * READERS);
  const conversations = readerConversations[index];
  api.history(data.tokens[SENDERS + index], conversations[Math.floor(Math.random() * conversations.length)]);
}
