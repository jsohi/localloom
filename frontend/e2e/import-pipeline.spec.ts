import { expect, test, type APIRequestContext } from '@playwright/test';

const API = 'http://localhost:8080';
const FIXTURE_RSS = 'http://test-fixtures/rss-feed.xml';

let sourceId: string;
let conversationId: string;
let api: APIRequestContext;

test.describe.serial('Import Pipeline', () => {
  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: API });
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test('import source from test RSS feed', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByLabel('URL').fill(FIXTURE_RSS);
    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill('E2E Test Podcast');

    await page.getByRole('dialog').getByRole('button', { name: 'Import' }).click();
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 });

    // Verify source was created via API
    // Wait briefly for the source to be persisted
    await page.waitForTimeout(2_000);
    const res = await api.get('/api/v1/sources');
    expect(res.status()).toBe(200);
    const sources = await res.json();
    expect(Array.isArray(sources)).toBe(true);
    const testSource = sources.find((s: { name: string }) => s.name === 'E2E Test Podcast');
    expect(testSource).toBeTruthy();
    sourceId = testSource.id;
  });

  test('job completes and episodes are indexed', async ({ page }) => {
    expect(sourceId).toBeTruthy();

    // Poll until sync completes (transcription takes time)
    let indexed = false;
    for (let i = 0; i < 60; i++) {
      const res = await api.get(`/api/v1/sources/${sourceId}`);
      const data = await res.json();

      if (data.source.syncStatus === 'IDLE' && data.contentUnits.length > 0) {
        if (data.contentUnits.every((u: { status: string }) => u.status === 'INDEXED')) {
          indexed = true;
          break;
        }
      }

      if (data.source.syncStatus === 'ERROR') {
        throw new Error('Import failed with ERROR status');
      }

      await page.waitForTimeout(5_000);
    }

    expect(indexed).toBe(true);

    // Verify episodes visible in UI
    await page.goto(`/library/${sourceId}`);
    await expect(page.getByText('Test Episode One')).toBeVisible({ timeout: 15_000 });
  });

  test('query indexed content via chat', async ({ page }) => {
    expect(sourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What content has been indexed?', { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    // Wait for real Ollama streaming response (may take a while)
    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 120_000 });

    // Track conversation for cleanup
    const convsRes = await api.get('/api/v1/conversations');
    const convs = await convsRes.json();
    if (convs.length > 0) {
      conversationId = convs[0].id;
    }
  });

  test('cleanup: delete source and conversations', async () => {
    if (sourceId) {
      const res = await api.delete(`/api/v1/sources/${sourceId}`);
      expect(res.ok()).toBe(true);
    }

    if (conversationId) {
      await api.delete(`/api/v1/conversations/${conversationId}`);
    }

    // Verify source is gone
    const res = await api.get('/api/v1/sources');
    const sources = await res.json();
    expect(sources.find((s: { id: string }) => s.id === sourceId)).toBeUndefined();
  });
});
