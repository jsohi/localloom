import type {
  Source,
  Job,
  ConnectorInfo,
  CreateSourceResponse,
  SyncSourceResponse,
  SourceDetailResponse,
} from './types';

const API_BASE = '/api/v1';

export async function fetchApi<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const url = `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`;
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  });

  if (!response.ok) {
    throw new Error(`API error ${response.status}: ${response.statusText}`);
  }

  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

// Connectors

export function getConnectors(): Promise<ConnectorInfo[]> {
  return fetchApi<ConnectorInfo[]>('/connectors');
}

// Sources

export function getSources(): Promise<Source[]> {
  return fetchApi<Source[]>('/sources');
}

export function getSource(id: string): Promise<SourceDetailResponse> {
  return fetchApi<SourceDetailResponse>(`/sources/${id}`);
}

export function createSource(body: {
  sourceType: string;
  name: string;
  originUrl: string;
  config?: string;
}): Promise<CreateSourceResponse> {
  return fetchApi<CreateSourceResponse>('/sources', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function syncSource(id: string): Promise<SyncSourceResponse> {
  return fetchApi<SyncSourceResponse>(`/sources/${id}/sync`, {
    method: 'POST',
  });
}

export function deleteSource(id: string): Promise<void> {
  return fetchApi<void>(`/sources/${id}`, { method: 'DELETE' });
}

// Jobs

export function getJobs(): Promise<Job[]> {
  return fetchApi<Job[]>('/jobs');
}

export function getJob(id: string): Promise<Job> {
  return fetchApi<Job>(`/jobs/${id}`);
}
