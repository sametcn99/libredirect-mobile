/**
 * Adapter for libredirect/instances's data.json: frontend id -> per-network
 * instance URL lists. Only `clearnet` is relevant here — this app routes
 * over plain HTTPS, not Tor/I2P/Lokinet — and only `https://` origin-only
 * URLs are usable at all, matching schema/routes.schema.json's
 * `instanceOrigin` definition.
 */

export interface LibRedirectInstanceNetworks {
  clearnet: string[];
  tor: string[];
  i2p: string[];
  loki: string[];
}

export type LibRedirectInstances = Record<string, LibRedirectInstanceNetworks>;

const INSTANCES_URL = "https://raw.githubusercontent.com/libredirect/instances/main/data.json";
const MAX_INSTANCES_PER_FRONTEND = 64;

export async function fetchLibRedirectInstances(): Promise<LibRedirectInstances> {
  const response = await fetch(INSTANCES_URL);
  if (!response.ok) {
    throw new Error(`Failed to fetch LibRedirect instances data.json: HTTP ${response.status}`);
  }
  return (await response.json()) as LibRedirectInstances;
}

const INSTANCE_ORIGIN_RE =
  /^https:\/\/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$/;

/** HTTPS-only clearnet instances that structurally match `instanceOrigin`, capped and deduplicated. */
export function httpsClearnetInstances(
  instances: LibRedirectInstanceNetworks | undefined,
): string[] {
  if (!instances) return [];
  const seen = new Set<string>();
  for (const url of instances.clearnet) {
    const trimmed = url.trim().replace(/\/+$/, "");
    if (INSTANCE_ORIGIN_RE.test(trimmed)) seen.add(trimmed);
    if (seen.size >= MAX_INSTANCES_PER_FRONTEND) break;
  }
  return [...seen];
}
