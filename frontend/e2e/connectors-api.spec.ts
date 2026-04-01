import { expect, test, type APIRequestContext } from '@playwright/test';

const API = process.env.API_URL || 'http://localhost:8080';

test.describe('Connectors API', () => {
  let api: APIRequestContext;

  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: API });
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test('GET /connectors returns all 5 connector types', async () => {
    const res = await api.get('/api/v1/connectors');
    expect(res.ok()).toBe(true);

    const connectors = await res.json();
    expect(connectors).toHaveLength(5);

    const types = connectors.map((c: { type: string }) => c.type);
    expect(types).toContain('PODCAST');
    expect(types).toContain('FILE_UPLOAD');
    expect(types).toContain('WEB_PAGE');
    expect(types).toContain('GITHUB');
    expect(types).toContain('TEAMS');
  });

  test('podcast, file upload, and web page are enabled by default', async () => {
    const res = await api.get('/api/v1/connectors');
    const connectors = await res.json();

    const podcast = connectors.find((c: { type: string }) => c.type === 'PODCAST');
    const fileUpload = connectors.find((c: { type: string }) => c.type === 'FILE_UPLOAD');
    const webPage = connectors.find((c: { type: string }) => c.type === 'WEB_PAGE');

    expect(podcast.enabled).toBe(true);
    expect(fileUpload.enabled).toBe(true);
    expect(webPage.enabled).toBe(true);
  });

  test('github and teams are disabled by default', async () => {
    const res = await api.get('/api/v1/connectors');
    const connectors = await res.json();

    const github = connectors.find((c: { type: string }) => c.type === 'GITHUB');
    const teams = connectors.find((c: { type: string }) => c.type === 'TEAMS');

    expect(github.enabled).toBe(false);
    expect(teams.enabled).toBe(false);
  });
});
