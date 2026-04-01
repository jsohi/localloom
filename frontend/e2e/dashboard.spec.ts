import { expect, test } from '@playwright/test';

test.describe('Dashboard', () => {
  test('dashboard loads with real service status', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'System Status' })).toBeVisible();
    await expect(page.locator('[data-slot="card"]').first()).toBeVisible({ timeout: 15_000 });
  });
});
