import { expect, test } from '@playwright/test';

test.describe('Navigation', () => {
  test('sidebar navigation works', async ({ page }) => {
    await page.goto('/');

    await page.getByRole('link', { name: 'Library' }).click();
    await expect(page).toHaveURL(/\/library/);

    await page.getByRole('link', { name: 'Chat' }).click();
    await expect(page).toHaveURL(/\/chat/);

    await page.getByRole('link', { name: 'Settings' }).click();
    await expect(page).toHaveURL(/\/settings/);

    await page.getByRole('link', { name: 'Dashboard' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('dark mode toggle works', async ({ page }) => {
    await page.goto('/');

    const toggleButton = page.getByLabel('Toggle theme');
    await expect(toggleButton).toBeVisible();

    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/dark/);

    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/light/);
  });
});
