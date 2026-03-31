import { expect, test } from '@playwright/test';

test.describe('Library', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/sources', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      }
      // POST — mock the create source response
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ id: 'src-1', job_id: 'job-1' }),
      });
    });
    await page.route('**/api/v1/jobs', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  });

  test('library page loads', async ({ page }) => {
    await page.goto('/library');

    await expect(page.getByRole('heading', { name: 'Podcast Library' })).toBeVisible();
  });

  test('import dialog opens and submits', async ({ page }) => {
    await page.goto('/library');

    // Click the "Import Podcast" button to open the dialog
    await page.getByRole('button', { name: 'Import Podcast' }).first().click();

    // Verify the dialog is visible
    await expect(page.getByRole('dialog')).toBeVisible();

    // Fill in the URL field
    await page.getByLabel('URL').fill('https://example.com/feed.xml');

    // Fill in the Name field (URL auto-populates it, clear first)
    await page.getByLabel('Name').clear();
    await page.getByLabel('Name').fill('Test Podcast');

    // Click Import button inside the dialog
    await page.getByRole('dialog').getByRole('button', { name: 'Import' }).click();

    // Dialog should close after successful submission
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 5_000 });
  });
});
