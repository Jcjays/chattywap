// A realistic mix of authenticated REST requests from many users at a fixed arrival rate: conversation lists, message
// history (first pages and cursor pages), sending, unread counts, receipts. Each iteration is one user action.
//
//   RATE      iterations (user actions) per second           default 50
//   DURATION  how long to keep that rate                     default 2m
//   USERS     how many seeded users act (users 1..USERS)     default 200; users 1-20 are power users, 1-100 in the hot
//             group
//   MAX_VUS   upper bound of concurrent virtual users        default 200
import { SharedArray } from 'k6/data';
import * as api from './lib/api.js';
import { directsOf, groupsOf } from './lib/seed.js';

const RATE = parseInt(__ENV.RATE || '50', 10);
const USERS = parseInt(__ENV.USERS || '200', 10);

export const options = {
  scenarios: {
    mix: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: Math.min(RATE, 100),
      maxVUs: parseInt(__ENV.MAX_VUS || '200', 10),
    },
  },
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Reported, not enforced: a run that crosses them still completes.
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
    'http_req_duration{name:messages:send}': ['p(95)<500'],
    'http_req_duration{name:conversations:list}': ['p(95)<500'],
    'http_req_duration{name:messages:history}': ['p(95)<500'],
  },
};

const conversations = new SharedArray('conversations', () => {
  const all = [];
  for (let n = 1; n <= USERS; n++) {
    all.push(groupsOf(n).concat(directsOf(n)));
  }
  return all;
});

export function setup() {
  const numbers = [];
  for (let n = 1; n <= USERS; n++) {
    numbers.push(n);
  }
  return { tokens: api.loginAll(numbers) };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export default function (data) {
  const index = Math.floor(Math.random() * USERS);
  const token = data.tokens[index];
  const conversationId = pick(conversations[index]);
  const roll = Math.random() * 100;

  if (roll < 25) {
    api.listConversations(token);
  }
  else if (roll < 30) {
    const first = api.listConversations(token);
    const cursor = first.status === 200 ? first.json('nextCursor') : null;
    if (cursor) {
      api.listConversations(token, cursor);
    }
  }
  else if (roll < 50) {
    api.history(token, conversationId);
  }
  else if (roll < 60) {
    // Scrolling back: the first page, then two older ones by cursor.
    let res = api.history(token, conversationId);
    for (let page = 0; page < 2 && res.status === 200 && res.json('nextCursor'); page++) {
      res = api.history(token, conversationId, res.json('nextCursor'));
    }
  }
  else if (roll < 75) {
    api.send(token, conversationId, `load test ${Date.now()} ${'x'.repeat(Math.floor(Math.random() * 200))}`);
  }
  else if (roll < 85) {
    api.unreadCount(token, conversationId);
  }
  else if (roll < 92) {
    // Opening a conversation and reading up to its newest message.
    const res = api.history(token, conversationId, null, 20);
    if (res.status === 200 && res.json('content.0.id')) {
      api.markRead(token, conversationId, res.json('content.0.id'));
    }
  }
  else if (roll < 97) {
    api.receipts(token, conversationId);
  }
  else {
    api.me(token);
  }
}
