import { expect, test } from '@playwright/test';

test.describe('URL Auto-Detection', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/library');
    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('YouTube URL shows YouTube detection badge', async ({ page }) => {
    await page.getByLabel('URL').fill('https://www.youtube.com/watch?v=abc123');
    await expect(page.getByText('YouTube video')).toBeVisible({ timeout: 10_000 });
  });

  test('RSS feed URL shows Media detection badge', async ({ page }) => {
    await page.getByLabel('URL').fill('http://test-fixtures/rss-feed.xml');
    await expect(page.getByText('Media / podcast feed')).toBeVisible({ timeout: 10_000 });
  });

  test('Web page URL shows Web Page detection badge', async ({ page }) => {
    await page.getByLabel('URL').fill('http://test-fixtures/test-page.html');
    await expect(page.getByText('Web page')).toBeVisible({ timeout: 10_000 });
  });

  test('switching to file mode shows file input', async ({ page }) => {
    await page.getByRole('button', { name: /Upload a File/ }).click();
    await expect(page.getByLabel('File')).toBeVisible();
    await expect(page.getByLabel('URL')).toBeHidden();
  });

  test('switching back to URL mode shows URL input', async ({ page }) => {
    await page.getByRole('button', { name: /Upload a File/ }).click();
    await expect(page.getByLabel('File')).toBeVisible();

    await page.getByRole('button', { name: /Paste a URL/ }).click();
    await expect(page.getByLabel('URL')).toBeVisible();
    await expect(page.getByLabel('File')).toBeHidden();
  });
});
