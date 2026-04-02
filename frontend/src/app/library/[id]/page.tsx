'use client';

import { useCallback, useEffect, useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { ArrowLeftIcon, MicIcon } from 'lucide-react';
import { toast } from 'sonner';

import { EpisodeItem } from '@/components/episode-item';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { SidebarTrigger } from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { getSource } from '@/lib/api';
import type { ContentUnit, Source, SyncStatus } from '@/lib/types';

function syncStatusVariant(
  status: SyncStatus,
): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'IDLE':
      return 'secondary';
    case 'SYNCING':
      return 'default';
    case 'ERROR':
      return 'destructive';
    default:
      return 'outline';
  }
}

function DetailSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Skeleton className="size-16 rounded-lg" />
        <div className="space-y-2">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-4 w-32" />
        </div>
      </div>
      <div className="space-y-3">
        <Skeleton className="h-14 w-full rounded-lg" />
        <Skeleton className="h-14 w-full rounded-lg" />
        <Skeleton className="h-14 w-full rounded-lg" />
      </div>
    </div>
  );
}

export default function SourceDetailPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const [source, setSource] = useState<Source | null>(null);
  const [contentUnits, setContentUnits] = useState<ContentUnit[]>([]);
  const [loading, setLoading] = useState(true);

  const loadSource = useCallback(async () => {
    try {
      const data = await getSource(params.id);
      setSource(data.source);
      setContentUnits(data.contentUnits);
    } catch {
      toast.error('Source not found');
      router.replace('/library');
      return;
    } finally {
      setLoading(false);
    }
  }, [params.id]);

  useEffect(() => {
    loadSource();
  }, [loadSource]);

  // Auto-refresh while source is syncing
  useEffect(() => {
    if (!source || source.syncStatus !== 'SYNCING') return;
    const interval = setInterval(loadSource, 3000);
    return () => clearInterval(interval);
  }, [source?.syncStatus, loadSource]);

  return (
    <>
      <header className="flex h-16 shrink-0 items-center gap-2 border-b px-4">
        <SidebarTrigger className="-ml-1" />
        <Separator orientation="vertical" className="mr-2 h-4" />
        <Button variant="ghost" size="sm" asChild>
          <Link href="/library">
            <ArrowLeftIcon className="mr-1 size-4" />
            Library
          </Link>
        </Button>
        {source && (
          <>
            <Separator orientation="vertical" className="mx-1 h-4" />
            <h1 className="truncate text-lg font-semibold">{source.name}</h1>
          </>
        )}
      </header>

      <main className="flex-1 space-y-6 p-6">
        {loading ? (
          <DetailSkeleton />
        ) : source ? (
          <>
            <div className="flex items-start gap-4">
              {source.iconUrl ? (
                <Image
                  src={source.iconUrl}
                  alt={source.name}
                  width={64}
                  height={64}
                  className="size-16 rounded-lg object-cover"
                  unoptimized
                />
              ) : (
                <div className="bg-muted flex size-16 items-center justify-center rounded-lg">
                  <MicIcon className="text-muted-foreground size-8" />
                </div>
              )}
              <div className="min-w-0 flex-1 space-y-1">
                <h2 className="text-2xl font-bold tracking-tight">{source.name}</h2>
                {source.description && (
                  <p className="text-muted-foreground text-sm">{source.description}</p>
                )}
                <div className="flex flex-wrap items-center gap-2 pt-1">
                  <Badge variant={syncStatusVariant(source.syncStatus)}>{source.syncStatus}</Badge>
                  <span className="text-muted-foreground text-sm">
                    {contentUnits.length} {contentUnits.length === 1 ? 'episode' : 'episodes'}
                  </span>
                  {source.lastSyncedAt && (
                    <span className="text-muted-foreground text-sm" suppressHydrationWarning>
                      Last synced {new Date(source.lastSyncedAt).toLocaleDateString()}
                    </span>
                  )}
                </div>
              </div>
            </div>

            <Separator />

            <div>
              <h3 className="mb-3 text-lg font-semibold">Episodes</h3>
              {contentUnits.length === 0 ? (
                <p className="text-muted-foreground text-sm">No episodes found.</p>
              ) : (
                <div className="space-y-2">
                  {contentUnits.map((unit) => (
                    <EpisodeItem key={unit.id} episode={unit} />
                  ))}
                </div>
              )}
            </div>
          </>
        ) : (
          <div className="flex flex-col items-center justify-center py-16">
            <h3 className="text-lg font-semibold">Source not found</h3>
            <p className="text-muted-foreground mb-4 text-sm">
              The source you are looking for does not exist.
            </p>
            <Button asChild>
              <Link href="/library">Back to Library</Link>
            </Button>
          </div>
        )}
      </main>
    </>
  );
}
