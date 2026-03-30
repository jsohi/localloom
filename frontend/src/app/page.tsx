export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-24">
      <h1 className="text-4xl font-bold tracking-tight text-foreground">
        LocalLoom
      </h1>
      <p className="mt-4 max-w-xl text-center text-lg text-muted-foreground">
        A privacy-first, locally-running knowledge base. Ingest content from
        podcasts, Confluence, MS Teams, GitHub, and file uploads — then ask
        questions across all your sources using a local LLM.
      </p>
    </main>
  );
}
