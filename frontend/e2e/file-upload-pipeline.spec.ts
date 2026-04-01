import { expect, test, type APIRequestContext } from '@playwright/test';
import path from 'path';

const API = process.env.API_URL || 'http://localhost:8080';
const SOURCE_NAME = 'E2E Test PDF Upload';

let sourceId: string;
let api: APIRequestContext;

test.describe.serial('File Upload Pipeline', () => {
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

  test('upload a text file via the API', async () => {
    const res = await api.fetch(`${API}/api/v1/sources/upload`, {
      method: 'POST',
      multipart: {
        file: {
          name: 'test-upload.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from(
            'LocalLoom E2E test content. This text verifies the file upload pipeline works end-to-end for knowledge base indexing.',
          ),
        },
        name: SOURCE_NAME,
        sourceType: 'FILE_UPLOAD',
      },
    });

    expect(res.ok()).toBe(true);
    const body = await res.json();
    expect(body.source_id).toBeTruthy();
    expect(body.job_id).toBeTruthy();
    sourceId = body.source_id;
  });

  test('uploaded source appears with INDEXED content', async () => {
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
        { timeout: 30_000, intervals: [1_000] },
      )
      .toBe('INDEXED');
  });

  test('uploaded content is searchable via chat', async ({ page }) => {
    test.setTimeout(120_000);
    expect(sourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What does the E2E test content say?', { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 90_000 });
  });
});
