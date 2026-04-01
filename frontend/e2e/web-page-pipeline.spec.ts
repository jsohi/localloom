import { expect, test, type APIRequestContext } from '@playwright/test';

const API = 'http://localhost:8080';
const FIXTURE_URL = 'http://test-fixtures/test-page.html';
const SOURCE_NAME = 'E2E Test Web Page';

let sourceId: string;
let api: APIRequestContext;

test.describe.serial('Web Page Pipeline', () => {
  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: API });

    // Clean up stale sources from previous runs
    const res = await api.get('/api/v1/sources');
    if (res.ok()) {
      const sources = await res.json();
      for (const s of sources.filter((s: { name: string }) => s.name === SOURCE_NAME)) {
        await api.delete(`/api/v1/sources/${s.id}`);
      }
    }
  });

  test.afterAll(async () => {
    if (sourceId) {
      await api.delete(`/api/v1/sources/${sourceId}`).catch(() => {});
    }
    await api.dispose();
  });

  test('import web page via UI', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // Select Web Page type
    await page.getByLabel('Source Type').click();
    await page.getByRole('option', { name: /Web Page/ }).click();

    await page.getByLabel('Page URL').fill(FIXTURE_URL);
    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill(SOURCE_NAME);

    await page.getByRole('dialog').getByRole('button', { name: 'Import Page' }).click();
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
    sourceId = sources.find((s: { name: string }) => s.name === SOURCE_NAME).id;
  });

  test('web page is indexed with sections', async () => {
    test.setTimeout(60_000);
    expect(sourceId).toBeTruthy();

    await expect
      .poll(
        async () => {
          const res = await api.get(`/api/v1/sources/${sourceId}`);
          const data = await res.json();
          if (data.contentUnits?.length > 0) {
            return data.contentUnits[0].status;
          }
          return null;
        },
        { timeout: 50_000, intervals: [2_000] },
      )
      .toBe('INDEXED');

    // Verify the content unit has the page title
    const res = await api.get(`/api/v1/sources/${sourceId}`);
    const data = await res.json();
    expect(data.contentUnits[0].rawText).toContain('quantum entanglement scheduler');
  });

  test('query web page content via chat', async ({ page }) => {
    test.setTimeout(120_000);
    expect(sourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What is the quantum entanglement scheduler?', { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 90_000 });
  });
});
