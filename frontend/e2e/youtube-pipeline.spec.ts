import { expect, test, type APIRequestContext } from '@playwright/test';

const API = process.env.API_URL || 'http://localhost:8080';
const YOUTUBE_URL = 'https://www.youtube.com/watch?v=e2e_test_video';
const SOURCE_NAME = 'E2E Test YouTube Video';
const E2E_CHAT_QUERY = 'What is in the YouTube video?';

let sourceId: string;
let testConversationIds: string[] = [];
let api: APIRequestContext;

test.describe.serial('YouTube Pipeline', () => {
  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: API });

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
    if (sourceId) {
      await api.delete(`/api/v1/sources/${sourceId}`).catch(() => {});
    }
    for (const id of testConversationIds) {
      await api.delete(`/api/v1/conversations/${id}`).catch(() => {});
    }
    await api.dispose();
  });

  test('import YouTube video via UI with auto-detection', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByLabel('URL').fill(YOUTUBE_URL);

    // Wait for auto-detection badge
    await expect(page.getByText('YouTube video')).toBeVisible({ timeout: 10_000 });

    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill(SOURCE_NAME);

    await page.getByRole('dialog').getByRole('button', { name: /^Import$/ }).click();
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 });

    // Poll API until source appears
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
    const source = sources.find((s: { name: string }) => s.name === SOURCE_NAME);
    sourceId = source.id;

    // Verify auto-detected as YOUTUBE
    expect(source.sourceType).toBe('YOUTUBE');
  });

  test('YouTube video is transcribed and indexed', async ({ page }) => {
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

    // Verify episode appears on source detail page
    await page.goto(`/library/${sourceId}`);
    await expect(page.getByRole('heading', { name: 'E2E Test YouTube Video' }).first()).toBeVisible({ timeout: 15_000 });
  });

  test('query YouTube transcription via chat', async ({ page }) => {
    test.setTimeout(120_000);
    expect(sourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially(E2E_CHAT_QUERY, { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    // Wait for real Ollama streaming response
    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 90_000 });

    const convRes = await api.get('/api/v1/conversations');
    if (convRes.ok()) {
      const convs = await convRes.json();
      testConversationIds = convs
        .filter((c: { title: string }) => c.title === E2E_CHAT_QUERY)
        .map((c: { id: string }) => c.id);
    }
  });
});
