import { useEffect, useRef, useState } from "react";
import { ApiError, request } from "./api";

/** Shared transport state; each scenario keeps its own actions and rules. */
export function useScenario<T>(statusPath: string, name: string) {
  const [status, setStatus] = useState<T | null>(null);
  const [connection, setConnection] = useState<"connecting" | "online" | "offline">("connecting");
  const [pollError, setPollError] = useState<string | null>(null);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [retry, setRetry] = useState(0);
  const pendingRef = useRef(false);
  const generation = useRef(0);
  const pollController = useRef<AbortController | null>(null);
  const commandController = useRef<AbortController | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      commandController.current?.abort();
    };
  }, []);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (!active) return;
      if (pendingRef.current) {
        timer = setTimeout(poll, 2000);
        return;
      }
      const epoch = generation.current;
      const controller = new AbortController();
      pollController.current = controller;
      const timeout = setTimeout(() => controller.abort(), 4000);
      try {
        const result = await request<T>(statusPath, controller.signal);
        if (active && epoch === generation.current) {
          setStatus(result);
          setConnection("online");
          setPollError(null);
        }
      } catch (cause) {
        if (active && epoch === generation.current) {
          setConnection("offline");
          setPollError(controller.signal.aborted ? `${name} bağlantısı zaman aşımına uğradı.` : (cause as Error).message);
        }
      } finally {
        clearTimeout(timeout);
        if (pollController.current === controller) pollController.current = null;
        if (active) timer = setTimeout(poll, 2000);
      }
    }
    void poll();
    return () => {
      active = false;
      clearTimeout(timer);
      pollController.current?.abort();
    };
  }, [statusPath, name, retry]);

  async function command(path: string, successMessage: string, body?: unknown) {
    if (pendingRef.current || connection !== "online") return;
    pendingRef.current = true;
    setPending(true);
    setNotice(null);
    setCommandError(null);
    generation.current += 1;
    pollController.current?.abort();
    const controller = new AbortController();
    commandController.current = controller;
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const result = await request<T>(path, controller.signal, "POST", body);
      if (!mounted.current) return;
      setStatus(result);
      setConnection("online");
      setPollError(null);
      setNotice(successMessage);
    } catch (cause) {
      if (!mounted.current) return;
      setCommandError(controller.signal.aborted ? `${name} isteği zaman aşımına uğradı. Yeniden işlem yapmadan önce durumu kontrol et.` : (cause as Error).message);
      if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) {
        // A rejected action still reached the service. Refresh the actual state,
        // especially after a stale expectedState, without retrying the command.
        setConnection("online");
        setRetry(value => value + 1);
      } else {
        setConnection("offline");
      }
    } finally {
      clearTimeout(timeout);
      pendingRef.current = false;
      if (commandController.current === controller) commandController.current = null;
      if (mounted.current) setPending(false);
    }
  }

  return {
    status, connection, pollError, commandError, notice, pending, command,
    refresh: () => setRetry(value => value + 1),
  };
}
