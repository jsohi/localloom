import { expect, test } from '@playwright/test';

test.describe('Import Dialog Fields', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/library');
    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('URL mode shows URL input and name by default', async ({ page }) => {
    await expect(page.getByLabel('URL')).toBeVisible();
    await expect(page.getByLabel('Name')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Import$/ })).toBeVisible();
  });

  test('file mode shows file picker and name', async ({ page }) => {
    await page.getByRole('button', { name: /Upload a File/ }).click();

    await expect(page.getByLabel('File')).toBeVisible();
    await expect(page.getByLabel('Name')).toBeVisible();
    await expect(page.getByLabel('URL')).toBeHidden();
    await expect(page.getByRole('button', { name: /Upload & Import/ })).toBeVisible();
  });

  test('switching back to URL mode hides file input', async ({ page }) => {
    await page.getByRole('button', { name: /Upload a File/ }).click();
    await expect(page.getByLabel('File')).toBeVisible();

    await page.getByRole('button', { name: /Paste a URL/ }).click();
    await expect(page.getByLabel('URL')).toBeVisible();
    await expect(page.getByLabel('File')).toBeHidden();
  });
});
