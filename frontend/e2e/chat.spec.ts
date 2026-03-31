import { expect, test } from '@playwright/test';

test.describe('Chat', () => {
  test('chat page loads with empty state', async ({ page }) => {
    await page.route(/\/api\/v1\/conversations$/, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );

    await page.goto('/chat');

    await expect(page.getByText('Ask anything')).toBeVisible();
    await expect(
      page.getByText('Query your knowledge base across all indexed sources.'),
    ).toBeVisible();
  });

  test('can send a message and receive a streamed response', async ({ page }) => {
    let conversationCreated = false;

    // Mock conversations list — returns conv-1 after the query completes
    await page.route(/\/api\/v1\/conversations$/, (route) => {
      const body = conversationCreated
        ? JSON.stringify([{ id: 'conv-1', title: 'What is testing?', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }])
        : '[]';
      return route.fulfill({ status: 200, contentType: 'application/json', body });
    });

    // Mock the conversation detail endpoint (loaded after ChatView remounts with new conversationId)
    await page.route('**/api/v1/conversations/conv-1', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'conv-1',
          title: 'What is testing?',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          messages: [
            { id: 'usr-1', role: 'USER', content: 'What is testing?', createdAt: new Date().toISOString() },
            { id: 'msg-1', role: 'ASSISTANT', content: 'Hello from the assistant!', createdAt: new Date().toISOString() },
          ],
        }),
      }),
    );

    // Mock the SSE streaming endpoint
    await page.route('**/api/v1/query', (route) => {
      conversationCreated = true;

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

    // Type a message using pressSequentially to trigger React onChange
    const textarea = page.getByPlaceholder('Ask a question about your knowledge base...');
    await textarea.click();
    await textarea.pressSequentially('What is testing?', { delay: 20 });

    // Verify send button is enabled before clicking
    const sendButton = page.getByRole('button', { name: 'Send' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    // Verify the user message appears in the chat area (not the sidebar title)
    await expect(page.getByRole('paragraph').filter({ hasText: 'What is testing?' })).toBeVisible({ timeout: 10_000 });

    // Wait for the assistant response to render
    await expect(page.getByText('Hello from the assistant!')).toBeVisible({ timeout: 10_000 });
  });
});
