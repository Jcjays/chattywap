// What loadtest/seed.sql created, computed instead of looked up: every ID is the md5 of a name, as a UUID.
import crypto from 'k6/crypto';

export const SEED_USERS = parseInt(__ENV.SEED_USERS || '2000', 10);
export const SEED_GROUPS = parseInt(__ENV.SEED_GROUPS || '200', 10);
export const POWER_USERS = 20;
export const POWER_USER_DIRECTS = 300;
export const HOT_GROUP_MEMBERS = 100;
export const PASSWORD = __ENV.SEED_PASSWORD || 'loadtest-password';

// PostgreSQL's md5(text)::uuid.
export function nameUuid(name) {
  const h = crypto.md5(name, 'hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function email(n) {
  return `lt-${n}@loadtest.invalid`;
}

export function userId(n) {
  return nameUuid(`lt-user-${n}`);
}

export function groupId(g) {
  return nameUuid(`lt-group-${g}`);
}

export const HOT_GROUP_ID = groupId(1);

function wrap(n) {
  return ((n - 1 + SEED_USERS) % SEED_USERS) + 1;
}

/** The direct conversations of user n: with the five users after it, the five before it, and a power user's extras. */
export function directsOf(n) {
  const ids = [];
  for (let d = 1; d <= 5; d++) {
    ids.push(nameUuid(`lt-direct-${n}-${wrap(n + d)}`));
    ids.push(nameUuid(`lt-direct-${wrap(n - d)}-${n}`));
  }
  if (n <= POWER_USERS) {
    for (let j = 1; j <= POWER_USER_DIRECTS; j++) {
      ids.push(nameUuid(`lt-direct-${n}-${n * 50 + j + 5}`));
    }
  }
  else if (n > 55) {
    for (let p = 1; p <= POWER_USERS; p++) {
      const j = n - 5 - p * 50;
      if (j >= 1 && j <= POWER_USER_DIRECTS) {
        ids.push(nameUuid(`lt-direct-${p}-${n}`));
      }
    }
  }
  return ids;
}

/** The members of group g (2 or more; group 1, the hot group, is users 1-100). */
export function membersOf(g) {
  const size = 10 + ((g * 37) % 91);
  const members = [];
  for (let k = 0; k < size; k++) {
    members.push(((g * 13 + k * 7) % SEED_USERS) + 1);
  }
  return members;
}

/** The groups user n belongs to, the hot group included. */
export function groupsOf(n) {
  const ids = n <= HOT_GROUP_MEMBERS ? [HOT_GROUP_ID] : [];
  for (let g = 2; g <= SEED_GROUPS; g++) {
    const size = 10 + ((g * 37) % 91);
    for (let k = 0; k < size; k++) {
      if (((g * 13 + k * 7) % SEED_USERS) + 1 === n) {
        ids.push(groupId(g));
        break;
      }
    }
  }
  return ids;
}
