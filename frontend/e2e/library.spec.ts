import { expect, test } from '@playwright/test';

test.describe('Library', () => {
  test('library page loads', async ({ page }) => {
    await page.goto('/library');

    await expect(page.getByRole('heading', { name: 'Library', level: 1 })).toBeVisible();
  });

  test('import dialog opens and can be filled', async ({ page }) => {
    await page.goto('/library');

    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByLabel('URL').fill('http://test-fixtures/rss-feed.xml');
    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill('E2E Test Source');

    // Verify form fields are populated
    await expect(page.getByLabel('URL')).toHaveValue('http://test-fixtures/rss-feed.xml');
    await expect(page.getByLabel('Name')).toHaveValue('E2E Test Source');

    // Close without submitting (import-pipeline test handles the real import)
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
  });
});
