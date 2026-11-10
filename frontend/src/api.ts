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
  fireAlarmActive: boolean;
  recoveryRequired: boolean;
  observedAtUnixMs: number;
}

export interface DoorStatus {
  doorId: string;
  mode: "simulation";
  state: string;
  doorOpen: boolean;
  passageAllowed: boolean;
  robotInDoorway: boolean;
  fireAlarmActive: boolean;
  recoveryRequired: boolean;
  observedAtUnixMs: number;
}

export interface FireAlarmStatus {
  alarmId: string;
  mode: "simulation";
  active: boolean;
  recoveryRequired: boolean;
  doorCancelled: boolean;
  elevatorCancelled: boolean;
  observedAtUnixMs: number;
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
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
    throw new ApiError(
      response.status,
      error?.message ?? `Bağlantı isteği başarısız (${response.status}).`,
    );
  }
  return response.json() as Promise<T>;
}
