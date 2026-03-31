'use client';

import { useEffect, useState } from 'react';
import { LoaderCircleIcon } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { getJobs } from '@/lib/api';
import type { Job, JobStatus } from '@/lib/types';

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

interface JobTrackerProps {
  readonly refreshKey?: number;
}

export function JobTracker({ refreshKey }: JobTrackerProps) {
  const [jobs, setJobs] = useState<Job[]>([]);

  useEffect(() => {
    async function fetchJobs() {
      try {
        const allJobs = await getJobs();
        const activeJobs = allJobs.filter(
          (j) => j.status === 'PENDING' || j.status === 'RUNNING',
        );
        setJobs(activeJobs);
      } catch {
        // Silently ignore polling errors
      }
    }

    fetchJobs();
    const intervalId = setInterval(fetchJobs, 5000);

    return () => clearInterval(intervalId);
  }, [refreshKey]);

  if (jobs.length === 0) return null;

  return (
    <div className="space-y-3">
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <LoaderCircleIcon className="size-4 animate-spin" />
        Active Jobs ({jobs.length})
      </h3>
      <div className="space-y-2">
        {jobs.map((job) => (
          <div key={job.id} className="bg-muted/50 rounded-lg border p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="truncate text-sm font-medium">{job.type}</span>
              <Badge variant={jobStatusVariant(job.status)} className="ml-2">
                {job.status}
              </Badge>
            </div>
            <Progress value={job.progress * 100} className="h-2" />
            <p className="text-muted-foreground mt-1 text-xs">
              {Math.round(job.progress * 100)}% complete
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}
