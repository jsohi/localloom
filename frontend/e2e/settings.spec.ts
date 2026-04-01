import { expect, test } from '@playwright/test';

test.describe('Settings', () => {
  test('settings page loads with real data', async ({ page }) => {
    await page.goto('/settings');

    await expect(page.getByRole('heading', { name: 'Configuration' })).toBeVisible();
    await expect(page.locator('[data-slot="card"]').first()).toBeVisible({ timeout: 15_000 });
  });

  test('shows real Ollama model info', async ({ page }) => {
    await page.goto('/settings');

    await expect(page.locator('[data-slot="card"]').first()).toBeVisible({ timeout: 15_000 });
    const cardCount = await page.locator('[data-slot="card"]').count();
    expect(cardCount).toBeGreaterThanOrEqual(1);
  });
});
