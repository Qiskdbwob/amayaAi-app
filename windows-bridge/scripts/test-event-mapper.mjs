import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mapOpencodeEvent } from '../dist/agents/opencode/opencode-event-mapper.js';

test('message.part.updated text becomes MESSAGE_PART_TEXT', () => {
  const event = mapOpencodeEvent({
    type: 'message.part.updated',
    properties: {
      sessionID: 'sess-1',
      messageID: 'msg-1',
      part: {
        id: 'part-1',
        type: 'text',
        text: 'hello world',
        time: { end: 17 }
      }
    }
  });
  assert.ok(event);
  assert.equal(event.kind, 'message.part.text');
  assert.equal(event.sessionId, 'sess-1');
  assert.equal(event.data.text, 'hello world');
});

test('permission.asked is forwarded verbatim', () => {
  const event = mapOpencodeEvent({
    type: 'permission.asked',
    properties: {
      sessionID: 'sess-2',
      id: 'perm-1',
      title: 'Edit file?',
      kind: 'edit'
    }
  });
  assert.ok(event);
  assert.equal(event.kind, 'permission.asked');
  assert.equal(event.sessionId, 'sess-2');
  assert.equal(event.data.id, 'perm-1');
});

test('server.connected is dropped', () => {
  const event = mapOpencodeEvent({
    type: 'server.connected',
    properties: { timestamp: 0 }
  });
  assert.equal(event, null);
});

test('unknown event falls back to session.status', () => {
  const event = mapOpencodeEvent({
    type: 'something.unknown',
    properties: { foo: 'bar' }
  });
  assert.ok(event);
  assert.equal(event.kind, 'session.status');
  assert.equal(event.data.opencodeType, 'something.unknown');
});
