import { expect, test } from '@playwright/test';

test.describe('Import Dialog Fields', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/library');
    await page.getByRole('button', { name: /Import/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('podcast shows feed URL, name, and max episodes', async ({ page }) => {
    // Podcast is the default type
    await expect(page.getByLabel('Feed URL')).toBeVisible();
    await expect(page.getByLabel('Name')).toBeVisible();
    await expect(page.getByLabel(/Max Episodes/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Import Podcast/ })).toBeVisible();
  });

  test('file upload shows file picker and name', async ({ page }) => {
    await page.getByLabel('Source Type').click();
    await page.getByRole('option', { name: /File Upload/ }).click();

    await expect(page.getByLabel('File')).toBeVisible();
    await expect(page.getByLabel('Name')).toBeVisible();
    await expect(page.getByLabel(/Max Episodes/)).toBeHidden();
    await expect(page.getByRole('button', { name: /Upload & Import/ })).toBeVisible();
  });

  test('web page shows URL and name fields', async ({ page }) => {
    await page.getByLabel('Source Type').click();
    await page.getByRole('option', { name: /Page/ }).click();

    await expect(page.getByLabel('Page URL')).toBeVisible();
    await expect(page.getByLabel('Name')).toBeVisible();
    await expect(page.getByLabel(/Max Episodes/)).toBeHidden();
    await expect(page.getByRole('button', { name: /Import Page/ })).toBeVisible();
  });

  test('github shows URL and disabled hint when connector off', async ({ page }) => {
    await page.getByLabel('Source Type').click();
    await page.getByRole('option', { name: /GitHub/ }).click();

    await expect(page.getByText(/not enabled/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Import Repo/ })).toBeDisabled();
  });

  test('teams shows disabled hint when connector off', async ({ page }) => {
    await page.getByLabel('Source Type').click();
    await page.getByRole('option', { name: /Teams/ }).click();

    await expect(page.getByText(/not enabled/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Upload & Import/ })).toBeDisabled();
  });
});
