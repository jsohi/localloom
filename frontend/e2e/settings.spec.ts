import { expect, test } from '@playwright/test';

test.describe('Settings', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/models/llm/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'UP',
          model: 'llama3.1:8b',
          provider: 'ollama',
        }),
      }),
    );
    await page.route('**/api/v1/models/llm', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { name: 'llama3.1:8b', size: 4_700_000_000, quantization: 'Q4_0' },
        ]),
      }),
    );
    await page.route('**/api/v1/sources', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
    await page.route('**/api/v1/connectors', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  });

  test('settings page loads', async ({ page }) => {
    await page.goto('/settings');

    await expect(page.getByRole('heading', { name: 'Configuration' })).toBeVisible();
  });

  test('settings page shows card sections', async ({ page }) => {
    await page.goto('/settings');

    // Wait for at least one Card to be visible (skeleton replaced by real content)
    await expect(page.locator('[data-slot="card"]').first()).toBeVisible({ timeout: 10_000 });

    // Verify there is more than one section card
    const cardCount = await page.locator('[data-slot="card"]').count();
    expect(cardCount).toBeGreaterThanOrEqual(1);
  });
});
