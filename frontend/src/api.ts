export interface RobotStatus {
  robotId: string;
  name: string;
  mode: "simulation" | "ros2";
  state: string;
  batteryPercent: number | null;
  linearVelocityMps: number | null;
  observedAtUnixMs: number;
}

export interface StopResponse {
  accepted: boolean;
  message: string;
  status: RobotStatus;
}

export interface ElevatorStatus {
  elevatorId: string;
  mode: "simulation";
  state: string;
  currentFloor: number;
  pickupFloor: number | null;
  destinationFloor: number | null;
  doorsOpen: boolean;
  robotInCabin: boolean;
  observedAtUnixMs: number;
}

export async function request<T>(
  path: string,
  signal: AbortSignal,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch(path, {
    method,
    signal,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as {
      message?: string;
    } | null;
    throw new Error(
      error?.message ?? `Bağlantı isteği başarısız (${response.status}).`,
    );
  }
  return response.json() as Promise<T>;
}
