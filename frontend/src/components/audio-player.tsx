'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { PauseIcon, PlayIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';

const SPEED_OPTIONS = [0.75, 1, 1.25, 1.5, 1.75, 2] as const;

interface AudioPlayerProps {
  readonly audioUrl: string;
}

function formatTime(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

export function AudioPlayer({ audioUrl }: AudioPlayerProps) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [speed, setSpeed] = useState(1);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const audio = new Audio(audioUrl);
    audioRef.current = audio;

    const onLoadedMetadata = () => setDuration(audio.duration);
    const onTimeUpdate = () => setCurrentTime(audio.currentTime);
    const onEnded = () => setIsPlaying(false);
    const onError = () => setError('Failed to load audio');

    audio.addEventListener('loadedmetadata', onLoadedMetadata);
    audio.addEventListener('timeupdate', onTimeUpdate);
    audio.addEventListener('ended', onEnded);
    audio.addEventListener('error', onError);

    return () => {
      audio.removeEventListener('loadedmetadata', onLoadedMetadata);
      audio.removeEventListener('timeupdate', onTimeUpdate);
      audio.removeEventListener('ended', onEnded);
      audio.removeEventListener('error', onError);
      audio.pause();
      audio.src = '';
    };
  }, [audioUrl]);

  const togglePlay = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;

    if (isPlaying) {
      audio.pause();
    } else {
      audio.play().catch(() => setError('Playback failed'));
    }
    setIsPlaying(!isPlaying);
  }, [isPlaying]);

  const handleSeek = useCallback((value: number[]) => {
    const audio = audioRef.current;
    if (!audio || !value[0]) return;
    audio.currentTime = value[0];
    setCurrentTime(value[0]);
  }, []);

  const cycleSpeed = useCallback(() => {
    setSpeed((prev) => {
      const currentIndex = SPEED_OPTIONS.indexOf(prev as (typeof SPEED_OPTIONS)[number]);
      const nextIndex = (currentIndex + 1) % SPEED_OPTIONS.length;
      const nextSpeed = SPEED_OPTIONS[nextIndex];
      if (audioRef.current) {
        audioRef.current.playbackRate = nextSpeed;
      }
      return nextSpeed;
    });
  }, []);

  if (error) {
    return (
      <div className="text-destructive mt-2 text-xs" role="alert">
        {error}
      </div>
    );
  }

  return (
    <div className="bg-background mt-2 flex items-center gap-2 rounded-lg border px-3 py-2">
      <Button
        variant="ghost"
        size="icon"
        className="size-7"
        onClick={togglePlay}
        aria-label={isPlaying ? 'Pause' : 'Play'}
      >
        {isPlaying ? <PauseIcon className="size-4" /> : <PlayIcon className="size-4" />}
      </Button>

      <span className="text-muted-foreground w-10 text-xs tabular-nums">
        {formatTime(currentTime)}
      </span>

      <Slider
        className="flex-1"
        min={0}
        max={duration || 1}
        step={0.1}
        value={[currentTime]}
        onValueChange={handleSeek}
        aria-label="Seek"
      />

      <span className="text-muted-foreground w-10 text-xs tabular-nums">
        {formatTime(duration)}
      </span>

      <Button
        variant="ghost"
        size="sm"
        className="h-7 w-12 px-1 text-xs font-medium"
        onClick={cycleSpeed}
        aria-label={`Playback speed ${speed}x`}
      >
        {speed}x
      </Button>
    </div>
  );
}
