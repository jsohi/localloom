import { expect, test } from '@playwright/test';

// Stub all common API calls so pages render without a running backend.
function mockApis(page: import('@playwright/test').Page) {
  return Promise.all([
    page.route('**/api/v1/models/llm/health', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"UP","model":"llama3.1:8b","provider":"ollama"}' }),
    ),
    page.route('**/api/v1/sources', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    ),
    page.route('**/api/v1/connectors', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    ),
    page.route('**/api/v1/jobs', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    ),
    page.route('**/api/v1/conversations', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    ),
    page.route('**/api/v1/models/llm', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    ),
  ]);
}

test.describe('Navigation', () => {
  test('sidebar navigation works', async ({ page }) => {
    await mockApis(page);
    await page.goto('/');

    // Navigate to Library
    await page.getByRole('link', { name: 'Library' }).click();
    await expect(page).toHaveURL(/\/library/);

    // Navigate to Chat
    await page.getByRole('link', { name: 'Chat' }).click();
    await expect(page).toHaveURL(/\/chat/);

    // Navigate to Settings
    await page.getByRole('link', { name: 'Settings' }).click();
    await expect(page).toHaveURL(/\/settings/);

    // Navigate back to Dashboard
    await page.getByRole('link', { name: 'Dashboard' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('dark mode toggle works', async ({ page }) => {
    await mockApis(page);
    await page.goto('/');

    // The ThemeToggle button has aria-label "Toggle theme"
    const toggleButton = page.getByLabel('Toggle theme');
    await expect(toggleButton).toBeVisible();

    // Click the toggle and verify the <html> element gets class "dark"
    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/dark/);

    // Toggle back to light
    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/light/);
  });
});
