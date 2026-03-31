'use client';

import { useState } from 'react';
import Link from 'next/link';
import { MicIcon, Trash2Icon } from 'lucide-react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { deleteSource } from '@/lib/api';
import type { Source, SyncStatus } from '@/lib/types';

interface SourceCardProps {
  readonly source: Source;
  readonly onDeleted: () => void;
}

function syncStatusVariant(status: SyncStatus): 'default' | 'secondary' | 'destructive' | 'outline' {
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

export function SourceCard({ source, onDeleted }: SourceCardProps) {
  const [deleting, setDeleting] = useState(false);

  const episodeCount = source.contentUnits?.length ?? 0;

  async function handleDelete() {
    setDeleting(true);
    try {
      await deleteSource(source.id);
      toast.success(`Deleted "${source.name}"`);
      onDeleted();
    } catch {
      toast.error(`Failed to delete "${source.name}"`);
    } finally {
      setDeleting(false);
    }
  }

  return (
    <Card className="group relative transition-colors hover:border-foreground/20">
      <Link href={`/library/${source.id}`} className="absolute inset-0 z-0" />

      <CardHeader className="flex flex-row items-start justify-between gap-2">
        <div className="flex items-center gap-3">
          {source.iconUrl ? (
            <img
              src={source.iconUrl}
              alt={source.name}
              className="size-10 rounded-md object-cover"
            />
          ) : (
            <div className="bg-muted flex size-10 items-center justify-center rounded-md">
              <MicIcon className="text-muted-foreground size-5" />
            </div>
          )}
          <div className="min-w-0 flex-1">
            <CardTitle className="truncate text-base">{source.name}</CardTitle>
            <p className="text-muted-foreground text-xs">
              {episodeCount} {episodeCount === 1 ? 'episode' : 'episodes'}
            </p>
          </div>
        </div>

        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              className="relative z-10 opacity-0 transition-opacity group-hover:opacity-100"
              disabled={deleting}
            >
              <Trash2Icon className="size-4" />
              <span className="sr-only">Delete source</span>
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete &ldquo;{source.name}&rdquo;?</AlertDialogTitle>
              <AlertDialogDescription>
                This will permanently remove this source and all of its indexed content. This action
                cannot be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={handleDelete} disabled={deleting}>
                {deleting ? 'Deleting...' : 'Delete'}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </CardHeader>

      <CardContent className="flex items-center gap-2">
        <Badge variant={syncStatusVariant(source.syncStatus)}>
          {source.syncStatus}
        </Badge>
        {source.lastSyncedAt && (
          <span className="text-muted-foreground text-xs" suppressHydrationWarning>
            Synced {new Date(source.lastSyncedAt).toLocaleDateString()}
          </span>
        )}
      </CardContent>
    </Card>
  );
}
