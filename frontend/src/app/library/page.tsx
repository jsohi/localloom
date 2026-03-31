'use client';

import { useCallback, useEffect, useState } from 'react';
import { BookOpenIcon } from 'lucide-react';
import { toast } from 'sonner';

import { ImportDialog } from '@/components/import-dialog';
import { JobTracker } from '@/components/job-tracker';
import { SourceCard } from '@/components/source-card';
import { Separator } from '@/components/ui/separator';
import { SidebarTrigger } from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { getSources } from '@/lib/api';
import type { Source } from '@/lib/types';

function SourceCardSkeleton() {
  return (
    <div className="rounded-xl border p-6">
      <div className="flex items-center gap-3">
        <Skeleton className="size-10 rounded-md" />
        <div className="flex-1 space-y-2">
          <Skeleton className="h-4 w-32" />
          <Skeleton className="h-3 w-20" />
        </div>
      </div>
      <div className="mt-4">
        <Skeleton className="h-5 w-16" />
      </div>
    </div>
  );
}

export default function LibraryPage() {
  const [sources, setSources] = useState<Source[]>([]);
  const [loading, setLoading] = useState(true);
  const [jobRefreshKey, setJobRefreshKey] = useState(0);

  const loadSources = useCallback(async () => {
    try {
      const data = await getSources();
      setSources(data);
    } catch {
      toast.error('Failed to load sources');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSources();
  }, [loadSources]);

  return (
    <>
      <header className="flex h-16 shrink-0 items-center gap-2 border-b px-4">
        <SidebarTrigger className="-ml-1" />
        <Separator orientation="vertical" className="mr-2 h-4" />
        <h1 className="text-lg font-semibold">Library</h1>
        <div className="ml-auto">
          <ImportDialog
            onImported={() => {
              loadSources();
              setJobRefreshKey((k) => k + 1);
            }}
          />
        </div>
      </header>

      <main className="flex-1 space-y-6 p-6">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Podcast Library</h2>
          <p className="text-muted-foreground">
            Manage your podcast sources and track indexing progress.
          </p>
        </div>

        <JobTracker refreshKey={jobRefreshKey} />

        {loading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <SourceCardSkeleton />
            <SourceCardSkeleton />
            <SourceCardSkeleton />
          </div>
        ) : sources.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed py-16">
            <BookOpenIcon className="text-muted-foreground mb-4 size-12" />
            <h3 className="text-lg font-semibold">No sources yet</h3>
            <p className="text-muted-foreground mb-4 text-sm">Import a podcast to get started.</p>
            <ImportDialog
              onImported={() => {
                loadSources();
                setJobRefreshKey((k) => k + 1);
              }}
            />
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {sources.map((source) => (
              <SourceCard key={source.id} source={source} onDeleted={loadSources} />
            ))}
          </div>
        )}
      </main>
    </>
  );
}
