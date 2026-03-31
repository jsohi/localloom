import { expect, test } from '@playwright/test';

test.describe('Chat', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/conversations', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  });

  test('chat page loads with empty state', async ({ page }) => {
    await page.goto('/chat');

    await expect(page.getByText('Ask anything')).toBeVisible();
    await expect(
      page.getByText('Query your knowledge base across all indexed sources.'),
    ).toBeVisible();
  });

  test('can send a message and receive a streamed response', async ({ page }) => {
    // Mock the SSE streaming endpoint
    await page.route('**/api/v1/query', (route) => {
      const sseBody = [
        'event: token\ndata: {"content":"Hello"}\n\n',
        'event: token\ndata: {"content":" from"}\n\n',
        'event: token\ndata: {"content":" the"}\n\n',
        'event: token\ndata: {"content":" assistant!"}\n\n',
        'event: sources\ndata: {"sources":[]}\n\n',
        'event: done\ndata: {"messageId":"msg-1","conversationId":"conv-1"}\n\n',
      ].join('');

      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    await page.goto('/chat');

    // Type a message in the chat textarea
    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.fill('What is testing?');

    // Click send
    await page.getByRole('button', { name: 'Send' }).click();

    // Verify the user message appears
    await expect(page.getByText('What is testing?')).toBeVisible();

    // Wait for the assistant response to render
    await expect(page.getByText('Hello from the assistant!')).toBeVisible({ timeout: 10_000 });
  });
});
