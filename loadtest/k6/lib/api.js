// REST calls of the API, tagged with a stable name so that k6 aggregates them per endpoint, not per URL.
import http from 'k6/http';
import { check, fail } from 'k6';
import { email, PASSWORD } from './seed.js';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function auth(token, extra) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}` }, extra || {}) };
}

function tagged(params, name) {
  params.tags = { name };
  return params;
}

/**
 * Logs in the seeded users with the given numbers and returns their access tokens, in the same order. Logins run in
 * parallel batches: BCrypt makes each one take about 0.1 s of CPU.
 */
export function loginAll(userNumbers) {
  const tokens = [];
  for (let i = 0; i < userNumbers.length; i += 20) {
    const chunk = userNumbers.slice(i, i + 20);
    const responses = http.batch(chunk.map((n) => ({
      method: 'POST',
      url: `${BASE_URL}/api/v1/auth/login`,
      body: JSON.stringify({ email: email(n), password: PASSWORD }),
      params: { headers: JSON_HEADERS, tags: { name: 'login' } },
    })));
    responses.forEach((res, j) => {
      if (res.status !== 200) {
        fail(`login of user ${chunk[j]} failed: ${res.status} ${res.body}`);
      }
      tokens.push(res.json('accessToken'));
    });
  }
  return tokens;
}

export function login(n) {
  const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email: email(n), password: PASSWORD }),
    { headers: JSON_HEADERS, tags: { name: 'login' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res;
}

export function listConversations(token, cursor) {
  const query = cursor ? `size=20&cursor=${encodeURIComponent(cursor)}` : 'size=20';
  const name = cursor ? 'conversations:list-cursor' : 'conversations:list';
  const res = http.get(`${BASE_URL}/api/v1/conversations?${query}`, tagged(auth(token), name));
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

export function history(token, conversationId, cursor, size, explicitQuery) {
  const query = explicitQuery || (`size=${size || 50}` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''));
  const name = cursor ? 'messages:history-cursor' : 'messages:history';
  const res = http.get(`${BASE_URL}/api/v1/conversations/${conversationId}/messages?${query}`, tagged(auth(token), name));
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

export function send(token, conversationId, content, name, baseUrl) {
  const res = http.post(`${baseUrl || BASE_URL}/api/v1/conversations/${conversationId}/messages`,
    JSON.stringify({ content }), tagged(auth(token, JSON_HEADERS), name || 'messages:send'));
  check(res, { 'send 201': (r) => r.status === 201 });
  return res;
}

export function unreadCount(token, conversationId) {
  const res = http.get(`${BASE_URL}/api/v1/conversations/${conversationId}/unread-count`,
    tagged(auth(token), 'receipts:unread-count'));
  check(res, { 'unread-count 200': (r) => r.status === 200 });
  return res;
}

export function receipts(token, conversationId) {
  const res = http.get(`${BASE_URL}/api/v1/conversations/${conversationId}/receipts`,
    tagged(auth(token), 'receipts:list'));
  check(res, { 'receipts 200': (r) => r.status === 200 });
  return res;
}

export function markRead(token, conversationId, messageId) {
  const res = http.post(`${BASE_URL}/api/v1/conversations/${conversationId}/receipts/read`,
    JSON.stringify({ messageId }), tagged(auth(token, JSON_HEADERS), 'receipts:read'));
  check(res, { 'read 200': (r) => r.status === 200 });
  return res;
}

export function me(token) {
  const res = http.get(`${BASE_URL}/api/v1/users/me`, tagged(auth(token), 'users:me'));
  check(res, { 'me 200': (r) => r.status === 200 });
  return res;
}
