import { SidebarTrigger } from '@/components/ui/sidebar';
import { Separator } from '@/components/ui/separator';
import { SettingsView } from '@/components/settings-view';

export default function SettingsPage() {
  return (
    <>
      <header className="flex h-16 shrink-0 items-center gap-2 border-b px-4">
        <SidebarTrigger className="-ml-1" />
        <Separator orientation="vertical" className="mr-2 h-4" />
        <h1 className="text-lg font-semibold">Settings</h1>
      </header>
      <main className="flex-1 space-y-6 p-6">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Configuration</h2>
          <p className="text-muted-foreground">
            Manage your LLM models, system status, and storage settings.
          </p>
        </div>
        <SettingsView />
      </main>
    </>
  );
}
