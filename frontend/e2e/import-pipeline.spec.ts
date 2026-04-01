import { expect, test, type APIRequestContext } from '@playwright/test';

const API = process.env.API_URL || 'http://localhost:8080';
const FIXTURE_RSS = 'http://test-fixtures/rss-feed.xml';
const SOURCE_NAME = 'E2E Test Podcast';
const E2E_CHAT_QUERY = 'What content has been indexed?';

let sourceId: string;
let testConversationIds: string[] = [];
let api: APIRequestContext;

test.describe.serial('Import Pipeline', () => {
  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: API });

    // Clean up any stale data from previous failed runs
    const res = await api.get('/api/v1/sources');
    if (res.ok()) {
      const sources = await res.json();
      for (const s of sources.filter((s: { name: string }) => s.name === SOURCE_NAME)) {
        await api.delete(`/api/v1/sources/${s.id}`);
      }
    }
    const convRes = await api.get('/api/v1/conversations');
    if (convRes.ok()) {
      const convs = await convRes.json();
      for (const c of convs.filter((c: { title: string }) => c.title === E2E_CHAT_QUERY)) {
        await api.delete(`/api/v1/conversations/${c.id}`);
      }
    }
  });

  test.afterAll(async () => {
    // Cleanup regardless of test outcome
    if (sourceId) {
      await api.delete(`/api/v1/sources/${sourceId}`).catch(() => {});
    }
    for (const id of testConversationIds) {
      await api.delete(`/api/v1/conversations/${id}`).catch(() => {});
    }
    await api.dispose();
  });

  test('import source from test RSS feed', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByLabel('URL').fill(FIXTURE_RSS);
    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill(SOURCE_NAME);

    await page.getByRole('dialog').getByRole('button', { name: 'Import' }).click();
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 });

    // Poll API until source appears (avoids TOCTOU with fixed delay)
    await expect
      .poll(
        async () => {
          const res = await api.get('/api/v1/sources');
          const sources = await res.json();
          return sources.find((s: { name: string }) => s.name === SOURCE_NAME);
        },
        { timeout: 10_000 },
      )
      .toBeTruthy();

    const res = await api.get('/api/v1/sources');
    const sources = await res.json();
    sourceId = sources.find((s: { name: string }) => s.name === SOURCE_NAME).id;
  });

  test('job completes and episodes are indexed', async ({ page }) => {
    test.setTimeout(300_000);
    expect(sourceId).toBeTruthy();

    await expect
      .poll(
        async () => {
          const res = await api.get(`/api/v1/sources/${sourceId}`);
          const data = await res.json();
          if (data.source.syncStatus === 'ERROR') throw new Error('Import failed with ERROR status');
          if (data.source.syncStatus === 'IDLE' && data.contentUnits.length > 0) {
            return data.contentUnits.every((u: { status: string }) => u.status === 'INDEXED');
          }
          return false;
        },
        { timeout: 290_000, intervals: [5_000] },
      )
      .toBe(true);

    await page.goto(`/library/${sourceId}`);
    await expect(page.getByText('Test Episode One')).toBeVisible({ timeout: 15_000 });
  });

  test('query indexed content via chat', async ({ page }) => {
    test.setTimeout(120_000);
    expect(sourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What content has been indexed?', { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    // Wait for real Ollama streaming response
    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 90_000 });

    // Track test-created conversations for cleanup
    const convRes = await api.get('/api/v1/conversations');
    if (convRes.ok()) {
      const convs = await convRes.json();
      testConversationIds = convs
        .filter((c: { title: string }) => c.title === E2E_CHAT_QUERY)
        .map((c: { id: string }) => c.id);
    }
  });
});
