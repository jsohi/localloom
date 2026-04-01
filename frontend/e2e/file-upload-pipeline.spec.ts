import { expect, test, type APIRequestContext } from '@playwright/test';
import path from 'path';

const API = process.env.API_URL || 'http://localhost:8080';
const PDF_SOURCE_NAME = 'E2E Test PDF Upload';
const TEXT_SOURCE_NAME = 'E2E Test Text Upload';
const FIXTURES_DIR = path.resolve(__dirname, '../../test-fixtures');

let pdfSourceId: string;
let textSourceId: string;
let testConversationIds: string[] = [];
let api: APIRequestContext;

test.describe.serial('File Upload Pipeline', () => {
  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: API });

    const res = await api.get('/api/v1/sources');
    if (res.ok()) {
      const sources = await res.json();
      for (const s of sources.filter(
        (s: { name: string }) => s.name === PDF_SOURCE_NAME || s.name === TEXT_SOURCE_NAME,
      )) {
        await api.delete(`/api/v1/sources/${s.id}`);
      }
    }
  });

  test.afterAll(async () => {
    if (pdfSourceId) await api.delete(`/api/v1/sources/${pdfSourceId}`).catch(() => {});
    if (textSourceId) await api.delete(`/api/v1/sources/${textSourceId}`).catch(() => {});
    for (const id of testConversationIds) {
      await api.delete(`/api/v1/conversations/${id}`).catch(() => {});
    }
    await api.dispose();
  });

  test('upload PDF via UI', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // Switch to file upload mode
    await page.getByRole('button', { name: /Upload a File/ }).click();

    // Upload the PDF fixture
    const fileInput = page.getByLabel('File');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, 'test-document.pdf'));

    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill(PDF_SOURCE_NAME);

    await page.getByRole('dialog').getByRole('button', { name: /Upload & Import/ }).click();
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 });

    await expect
      .poll(
        async () => {
          const res = await api.get('/api/v1/sources');
          const sources = await res.json();
          return sources.find((s: { name: string }) => s.name === PDF_SOURCE_NAME);
        },
        { timeout: 10_000 },
      )
      .toBeTruthy();

    const res = await api.get('/api/v1/sources');
    const sources = await res.json();
    pdfSourceId = sources.find((s: { name: string }) => s.name === PDF_SOURCE_NAME).id;
  });

  test('PDF content is indexed', async () => {
    expect(pdfSourceId).toBeTruthy();

    await expect
      .poll(
        async () => {
          const res = await api.get(`/api/v1/sources/${pdfSourceId}`);
          const data = await res.json();
          if (data.contentUnits?.length > 0) return data.contentUnits[0].status;
          return null;
        },
        { timeout: 30_000, intervals: [1_000] },
      )
      .toBe('INDEXED');
  });

  test('PDF content is searchable via chat', async ({ page }) => {
    test.setTimeout(120_000);
    expect(pdfSourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What does the test PDF document say?', { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 90_000 });
  });

  test('upload text file via UI', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByRole('button', { name: /Upload a File/ }).click();

    const fileInput = page.getByLabel('File');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, 'test-upload.txt'));

    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill(TEXT_SOURCE_NAME);

    await page.getByRole('dialog').getByRole('button', { name: /Upload & Import/ }).click();
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 });

    await expect
      .poll(
        async () => {
          const res = await api.get('/api/v1/sources');
          const sources = await res.json();
          return sources.find((s: { name: string }) => s.name === TEXT_SOURCE_NAME);
        },
        { timeout: 10_000 },
      )
      .toBeTruthy();

    const res = await api.get('/api/v1/sources');
    const sources = await res.json();
    textSourceId = sources.find((s: { name: string }) => s.name === TEXT_SOURCE_NAME).id;
  });

  test('text file content is indexed', async () => {
    expect(textSourceId).toBeTruthy();

    await expect
      .poll(
        async () => {
          const res = await api.get(`/api/v1/sources/${textSourceId}`);
          const data = await res.json();
          if (data.contentUnits?.length > 0) return data.contentUnits[0].status;
          return null;
        },
        { timeout: 30_000, intervals: [1_000] },
      )
      .toBe('INDEXED');

    // Verify content contains the unique phrase
    const res = await api.get(`/api/v1/sources/${textSourceId}`);
    const data = await res.json();
    expect(data.contentUnits[0].rawText).toContain('chromatic resonance detector');
  });

  test('text file content is searchable via chat', async ({ page }) => {
    test.setTimeout(120_000);
    expect(textSourceId).toBeTruthy();

    await page.goto('/chat');
    await expect(page.getByText('Ask anything')).toBeVisible();

    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What is the chromatic resonance detector?', { delay: 20 });

    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(
      page.locator('[class*="bg-muted"]').filter({ hasText: /.{10,}/ }),
    ).toBeVisible({ timeout: 90_000 });

    // Track conversations for cleanup
    const convRes = await api.get('/api/v1/conversations');
    if (convRes.ok()) {
      const convs = await convRes.json();
      testConversationIds = convs
        .filter(
          (c: { title: string }) =>
            c.title === 'What does the test PDF document say?' ||
            c.title === 'What is the chromatic resonance detector?',
        )
        .map((c: { id: string }) => c.id);
    }
  });
});
