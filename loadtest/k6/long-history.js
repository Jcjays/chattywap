// Opening long conversations: the members of the five groups with 200,000 messages each (groups 2-6 of seed.sql)
// load the first page of the history by page number, as a client opening a conversation does, at a fixed rate. Each
// such page also reports the conversation's total number of messages, which is what makes long conversations
// expensive to open if it is counted.
//
//   RATE      requests per second   default 50
//   DURATION  default 1m
//   PAGE      page number           default 0; deeper pages show what an offset costs
import { SharedArray } from 'k6/data';
import * as api from './lib/api.js';
import { groupId, membersOf } from './lib/seed.js';

const RATE = parseInt(__ENV.RATE || '50', 10);
const PAGE = parseInt(__ENV.PAGE || '0', 10);
const GROUPS = [2, 3, 4, 5, 6];

export const options = {
  scenarios: {
    open: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: Math.min(RATE, 100),
      maxVUs: 300,
    },
  },
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// One member of each group: who opens it.
const readers = new SharedArray('readers', () => GROUPS.map((g) => ({ group: groupId(g), user: membersOf(g)[0] })));

export function setup() {
  return { tokens: api.loginAll(readers.map((reader) => reader.user)) };
}

export default function (data) {
  const index = Math.floor(Math.random() * readers.length);
  const query = `page=${PAGE}&size=50`;
  const res = api.history(data.tokens[index], readers[index].group, null, 50, query);
  if (res.status === 200 && __ITER === 0) {
    console.log(`totalElements of group ${index + 2}: ${res.json('page.totalElements')}`);
  }
}
