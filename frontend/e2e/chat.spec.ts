import { expect, test } from '@playwright/test';

test.describe('Chat', () => {
  test('chat page loads with empty state', async ({ page }) => {
    await page.goto('/chat');

    await expect(page.getByText('Ask anything')).toBeVisible();
    await expect(
      page.getByText('Query your knowledge base across all indexed sources.'),
    ).toBeVisible();
  });
});
