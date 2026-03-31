import { expect, test } from '@playwright/test';

test.describe('Dashboard', () => {
  test('dashboard loads with status cards', async ({ page }) => {
    // Mock the health and sources API calls so the page renders without a backend
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
    await page.route('**/api/v1/sources', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    );
    await page.route('**/api/v1/connectors', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    );
    await page.route('**/api/v1/jobs', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    );

    await page.goto('/');

    // Verify "System Status" heading is visible
    await expect(page.getByRole('heading', { name: 'System Status' })).toBeVisible();

    // Wait for skeleton loaders to disappear and at least one card to appear
    await expect(page.locator('[data-slot="card"]').first()).toBeVisible({ timeout: 10_000 });
  });
});
