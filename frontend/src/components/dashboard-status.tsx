'use client';

import { useEffect, useState } from 'react';
import { ActivityIcon, DatabaseIcon, HardDriveIcon } from 'lucide-react';
import { toast } from 'sonner';

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { fetchApi, getSources } from '@/lib/api';
import type { Source } from '@/lib/types';

interface OllamaHealth {
  status: string;
  model?: string;
}

interface StatusCardProps {
  title: string;
  value: string;
  description: string;
  icon: React.ReactNode;
  status?: 'ok' | 'error' | 'loading';
}

function StatusCard({ title, value, description, icon, status = 'ok' }: StatusCardProps) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium">{title}</CardTitle>
        <div className="text-muted-foreground">{icon}</div>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-bold">{value}</div>
        <p className="text-muted-foreground text-xs">
          {status === 'error' && (
            <span className="bg-destructive mr-1 inline-block size-2 rounded-full" />
          )}
          {status === 'ok' && (
            <span className="mr-1 inline-block size-2 rounded-full bg-green-500" />
          )}
          {description}
        </p>
      </CardContent>
    </Card>
  );
}

function StatusCardSkeleton() {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="size-4" />
      </CardHeader>
      <CardContent>
        <Skeleton className="mb-2 h-8 w-16" />
        <Skeleton className="h-3 w-32" />
      </CardContent>
    </Card>
  );
}

export function DashboardStatus() {
  const [ollamaStatus, setOllamaStatus] = useState<'loading' | 'ok' | 'error'>('loading');
  const [ollamaModel, setOllamaModel] = useState<string>('');
  const [sources, setSources] = useState<Source[]>([]);
  const [sourcesLoading, setSourcesLoading] = useState(true);
  const [storageInfo, setStorageInfo] = useState<string>('--');

  useEffect(() => {
    async function checkOllama() {
      try {
        const health = await fetchApi<OllamaHealth>('/models/llm/health');
        if (health.status === 'UP' || health.status === 'ok') {
          setOllamaStatus('ok');
          setOllamaModel(health.model ?? 'Connected');
        } else {
          setOllamaStatus('error');
          setOllamaModel('Unavailable');
        }
      } catch {
        setOllamaStatus('error');
        setOllamaModel('Unavailable');
        toast.error('Failed to connect to Ollama. Is the service running?');
      }
    }

    async function loadSources() {
      try {
        const data = await getSources();
        setSources(data);
        const totalUnits = data.reduce((acc, s) => acc + (s.contentUnits?.length ?? 0), 0);
        setStorageInfo(`${totalUnits} content units`);
      } catch {
        toast.error('Failed to load sources');
      } finally {
        setSourcesLoading(false);
      }
    }

    checkOllama();
    loadSources();
  }, []);

  const isLoading = ollamaStatus === 'loading' || sourcesLoading;

  if (isLoading) {
    return (
      <div className="grid gap-4 md:grid-cols-3">
        <StatusCardSkeleton />
        <StatusCardSkeleton />
        <StatusCardSkeleton />
      </div>
    );
  }

  return (
    <div className="grid gap-4 md:grid-cols-3">
      <StatusCard
        title="Ollama Status"
        value={ollamaStatus === 'ok' ? 'Online' : 'Offline'}
        description={ollamaModel}
        icon={<ActivityIcon className="size-4" />}
        status={ollamaStatus}
      />
      <StatusCard
        title="Indexed Sources"
        value={String(sources.length)}
        description={`${sources.filter((s) => s.syncStatus === 'IDLE').length} synced`}
        icon={<DatabaseIcon className="size-4" />}
        status="ok"
      />
      <StatusCard
        title="Storage"
        value={storageInfo}
        description="Across all sources"
        icon={<HardDriveIcon className="size-4" />}
        status="ok"
      />
    </div>
  );
}
