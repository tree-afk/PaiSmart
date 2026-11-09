import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

const require = createRequire(import.meta.url);

function load(relativePath, mocks = {}) {
  const source = readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8')
    .replaceAll('import.meta.env', '{}');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  });
  const exports = {};
  runInNewContext(outputText, {
    exports,
    require: name => mocks[name] ?? require(name),
    console
  });
  return exports;
}

const { generatedRoutes } = load('src/router/elegant/routes.ts');
const { filterAuthRoutesByRoles } = load('src/store/modules/route/shared.ts', {
  '@/hooks/common/icon': {},
  '@/locales': {}
});

test('reader menus expose only user pages; management roles keep their own pages', () => {
  const names = (role, knowledgeRole) =>
    Array.from(filterAuthRoutesByRoles(generatedRoutes, role, knowledgeRole), route => route.name);
  const reader = names('USER', 'READER');
  for (const name of ['chat', 'personal-center', 'recharge']) assert.ok(reader.includes(name));
  for (const name of ['chat-history', 'user', 'invite-code', 'org-tag', 'model-provider',
    'recharge-manage', 'usage-monitor', 'knowledge-base', 'knowledge-roles']) {
    assert.ok(!reader.includes(name), `${name} must be hidden from readers`);
  }
  assert.ok(names('ADMIN', 'READER').includes('chat-history'));
  assert.ok(names('USER', 'FILE_MANAGER').includes('knowledge-base'));
  assert.ok(!names('USER', 'FILE_MANAGER').includes('knowledge-roles'));
  assert.ok(names('USER', 'ROLE_MANAGER').includes('knowledge-roles'));
  assert.ok(!names('USER', 'ROLE_MANAGER').includes('knowledge-base'));
});

test('403 preserves login and business errors; 401 clears expired login', () => {
  let handlers;
  let resets = 0;
  const messages = [];
  const { normalizeBackendMessage } = load('src/service/request/shared.ts', {
    '@/store/modules/auth': {}, '@/utils/storage': {}, '../api': {}
  });
  load('src/service/request/index.ts', {
    '@sa/axios': { BACKEND_ERROR_CODE: 'BACKEND_ERROR', createFlatRequest: (_, options) => {
      handlers = options;
      return { state: {} };
    } },
    '@/store/modules/auth': { useAuthStore: () => ({ resetStore: () => { resets += 1; } }) },
    '@/utils/service': { getServiceBaseURL: () => ({ baseURL: '/' }) },
    '@/locales': {},
    './shared': {
      getAuthorization: () => 'Bearer test',
      normalizeBackendMessage,
      showErrorMsg: (_, message) => messages.push(message)
    }
  });
  handlers.onError({ response: { status: 403, data: {} }, message: 'Forbidden' });
  assert.equal(resets, 0);
  assert.equal(messages.pop(), '无权访问该内容或执行此操作');
  handlers.onError({ code: 'BACKEND_ERROR', response: {
    status: 403, data: { message: 'INVITE_CODE_INVALID', code: 403 }
  } });
  assert.equal(resets, 0);
  assert.equal(messages.pop(), '邀请码不正确，请检查后重试');
  handlers.onError({ response: { status: 401, data: {} }, message: 'Unauthorized' });
  assert.equal(resets, 1);
});

test('login discards inaccessible saved destinations but keeps accessible destinations', async () => {
  let redirect = '/chat-history';
  let registered = false;
  let destination;
  const router = {
    currentRoute: { value: { query: {} } },
    push: async value => { destination = value; },
    resolve: path => {
      assert.ok(registered, 'resolve only after permission routes are registered');
      return { matched: [{}], name: path === '/chat' ? 'chat' : 'not-found' };
    }
  };
  const { useRouterPush } = load('src/hooks/common/router.ts', {
    'vue-router': { useRouter: () => router },
    '@/router': { router },
    '@/store/modules/route': { useRouteStore: () => ({ initAuthRoute: async () => { registered = true; } }) }
  });
  const { redirectFromLogin } = useRouterPush(false);
  router.currentRoute.value.query.redirect = redirect;
  await redirectFromLogin();
  assert.equal(destination.name, 'root');
  router.currentRoute.value.query.redirect = '/chat';
  await redirectFromLogin();
  assert.equal(destination, '/chat');
});
