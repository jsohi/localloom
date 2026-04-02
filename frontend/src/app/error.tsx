'use client';

import { useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { logger } from '@/lib/logger';

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    logger.error('Unhandled error:', error.message, error.digest);
  }, [error]);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 p-8">
      <h2 className="text-xl font-semibold">Something went wrong</h2>
      <p className="text-muted-foreground max-w-md text-center text-sm">
        An unexpected error occurred. Try refreshing the page or click below to retry.
      </p>
      {error.digest && (
        <code className="text-muted-foreground text-xs">Error ID: {error.digest}</code>
      )}
      <Button onClick={reset} variant="outline">
        Try again
      </Button>
    </div>
  );
}
