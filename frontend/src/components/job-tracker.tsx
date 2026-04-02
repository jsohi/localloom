'use client';

import { useEffect, useState } from 'react';
import { LoaderCircleIcon } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { getJobs, getSource } from '@/lib/api';
import type { ContentUnit, Job, JobStatus } from '@/lib/types';

function jobStatusVariant(status: JobStatus): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'PENDING':
      return 'outline';
    case 'RUNNING':
      return 'default';
    case 'COMPLETED':
      return 'secondary';
    case 'FAILED':
      return 'destructive';
    default:
      return 'outline';
  }
}

const STAGE_LABELS: Record<string, string> = {
  PENDING: 'Queued',
  FETCHING: 'Downloading',
  TRANSCRIBING: 'Transcribing',
  EXTRACTING: 'Extracting text',
  EMBEDDING: 'Embedding',
  INDEXED: 'Done',
  ERROR: 'Failed',
};

function stageLabel(status: string): string {
  return STAGE_LABELS[status] ?? status;
}

interface JobTrackerProps {
  readonly refreshKey?: number;
}

interface JobDetail {
  job: Job;
  sourceName?: string;
  totalUnits: number;
  completedUnits: number;
  currentUnit?: ContentUnit;
}

export function JobTracker({ refreshKey }: JobTrackerProps) {
  const [details, setDetails] = useState<JobDetail[]>([]);

  useEffect(() => {
    let isMounted = true;
    let timeoutId: ReturnType<typeof setTimeout>;

    async function fetchJobs() {
      try {
        const allJobs = await getJobs();
        if (!isMounted) return;
        // Filter active jobs. RUNNING jobs always show. PENDING jobs older than 5 minutes
        // are hidden (likely stale from a previous server crash or incomplete import).
        const STALE_MS = 5 * 60 * 1000;
        const now = Date.now();
        const activeJobs = allJobs.filter((j) => {
          if (j.status === 'RUNNING') return true;
          if (j.status === 'PENDING') {
            return now - new Date(j.createdAt).getTime() < STALE_MS;
          }
          return false;
        });

        const jobDetails: JobDetail[] = await Promise.all(
          activeJobs.map(async (job) => {
            try {
              const data = await getSource(job.entityId);
              const units: ContentUnit[] = data.contentUnits ?? [];
              const completed = units.filter(
                (u) => u.status === 'INDEXED' || u.status === 'ERROR',
              ).length;
              const current = units.find(
                (u) =>
                  u.status === 'FETCHING' ||
                  u.status === 'TRANSCRIBING' ||
                  u.status === 'EXTRACTING' ||
                  u.status === 'EMBEDDING',
              );
              return {
                job,
                sourceName: data.source?.name,
                totalUnits: units.length,
                completedUnits: completed,
                currentUnit: current,
              };
            } catch {
              return { job, totalUnits: 0, completedUnits: 0 };
            }
          }),
        );

        if (isMounted) setDetails(jobDetails);
      } catch {
        // Silently ignore polling errors
      } finally {
        if (isMounted) {
          timeoutId = setTimeout(fetchJobs, 3000);
        }
      }
    }

    fetchJobs();

    return () => {
      isMounted = false;
      if (timeoutId) clearTimeout(timeoutId);
    };
  }, [refreshKey]);

  if (details.length === 0) return null;

  return (
    <div className="space-y-3">
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <LoaderCircleIcon className="size-4 animate-spin" />
        Active Jobs ({details.length})
      </h3>
      <div className="space-y-2">
        {details.map(({ job, sourceName, totalUnits, completedUnits, currentUnit }) => (
          <div key={job.id} className="bg-muted/50 rounded-lg border p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="truncate text-sm font-medium">{sourceName ?? job.type}</span>
              <Badge variant={jobStatusVariant(job.status)} className="ml-2">
                {job.status}
              </Badge>
            </div>
            <Progress value={job.progress * 100} className="h-2" />
            <div className="text-muted-foreground mt-1 space-y-0.5 text-xs">
              <p>
                {completedUnits} of {totalUnits} items — {Math.round(job.progress * 100)}%
              </p>
              {currentUnit && (
                <p className="truncate">
                  {stageLabel(currentUnit.status)}: {currentUnit.title}
                </p>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
