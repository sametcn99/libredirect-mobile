#!/usr/bin/env bun
/**
 * Generates schema/routes.schema.json-conformant routes.json from live
 * LibRedirect upstream data. Per the project plan's converter principle
 * (§15): never guess. A target/frontend/service that can't be converted
 * with full confidence is dropped and reported, not approximated.
 *
 * Usage: bun run tools/src/generate-routes.ts [output-path]
 * Defaults to dist/routes.json (revision auto-increments from whatever
 * revision is already there, or starts at 1 if the file doesn't exist yet).
 */
import {
  type LibRedirectService,
  extractHostPattern,
  extractHosts,
  fetchLibRedirectConfig,
} from "./adapters/libredirect-config";
import {
  fetchLibRedirectInstances,
  httpsClearnetInstances,
} from "./adapters/libredirect-instances";

type RouteStrategy = { type: "replace-origin" } | { type: "passthrough" };

interface RouteFrontend {
  id: string;
  name: string;
  strategy: RouteStrategy;
  instances?: string[];
}

interface Route {
  id: string;
  name: string;
  hosts: string[];
  hostPatterns?: string[];
  frontends: RouteFrontend[];
}

interface Manifest {
  schemaVersion: 1;
  revision: number;
  generatedAt: string;
  routes: Route[];
}

function toKebabCase(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

interface ConvertibleFrontend {
  id: string;
  name: string;
  excludeTargets: Set<number>;
  instances: string[];
}

function convertService(
  serviceKey: string,
  service: LibRedirectService,
  instancesData: Awaited<ReturnType<typeof fetchLibRedirectInstances>>,
  log: (message: string) => void,
): Route[] {
  if (!service.targets?.length) {
    log(`${serviceKey}: skipped (no targets)`);
    return [];
  }

  // Map each matcher to every target index that produced it. A host reachable
  // via more than one target pattern (common: a service's primary pattern
  // and a narrower one for a different path shape both match the same
  // hostname) must be deduplicated *before* grouping, with its final
  // frontend applicability computed as the intersection across all of its
  // target indices — otherwise the same host could end up assigned to two
  // different groups/routes depending on which entry the grouping loop
  // happened to see, which is exactly the kind of ambiguity Phase 9's
  // "duplicate host" check exists to catch.
  const matcherToTargetIndices = new Map<string, Set<number>>();
  service.targets.forEach((target, targetIndex) => {
    const hosts = extractHosts(target);
    if (hosts) {
      for (const host of hosts) {
        const key = `host:${host}`;
        const indices = matcherToTargetIndices.get(key) ?? new Set<number>();
        indices.add(targetIndex);
        matcherToTargetIndices.set(key, indices);
      }
    } else {
      const hostPattern = extractHostPattern(target);
      if (hostPattern) {
        const key = `pattern:${hostPattern}`;
        const indices = matcherToTargetIndices.get(key) ?? new Set<number>();
        indices.add(targetIndex);
        matcherToTargetIndices.set(key, indices);
      } else {
        log(`${serviceKey}: target[${targetIndex}] unsupported: ${target}`);
      }
    }
  });
  if (matcherToTargetIndices.size === 0) {
    log(`${serviceKey}: skipped (no target produced a convertible host)`);
    return [];
  }

  const convertibleFrontends: ConvertibleFrontend[] = [];
  for (const [frontendKey, frontend] of Object.entries(service.frontends ?? {})) {
    if (!frontend.instanceList) {
      log(`${serviceKey}.${frontendKey}: skipped (not a hosted instance frontend)`);
      continue;
    }
    const instances = httpsClearnetInstances(instancesData[frontendKey]);
    if (instances.length === 0) {
      log(`${serviceKey}.${frontendKey}: skipped (no usable HTTPS clearnet instances)`);
      continue;
    }
    convertibleFrontends.push({
      id: toKebabCase(frontendKey),
      name: frontend.name,
      excludeTargets: new Set(frontend.excludeTargets ?? []),
      instances,
    });
  }
  const fallbackFrontend: RouteFrontend = {
    id: "original",
    name: "Original site",
    strategy: { type: "passthrough" },
  };

  // Group hosts by which frontends actually apply to them. A host is often
  // matched by more than one target pattern for the same reason a broader
  // pattern and a narrower, redundant one both exist (e.g. target[0]
  // already matches youtube.com/watch URLs generally, and target[3]
  // separately re-matches the same URLs more specifically) — a frontend
  // applies if it's valid for AT LEAST ONE of those patterns, not only if
  // it's valid for every one of them. The alternative (require all) was
  // tried first and rejected: it excluded Piped, one of the most-used
  // YouTube frontends, from the main youtube.com route purely because a
  // redundant secondary pattern for the same hosts happened to be in
  // Piped's excludeTargets, which produces a worse, more surprising result
  // than the ambiguity this union interpretation accepts instead.
  const groups = new Map<
    string,
    { frontendIds: string[]; hosts: string[]; hostPatterns: string[] }
  >();
  for (const [matcher, targetIndices] of matcherToTargetIndices) {
    const applicable = convertibleFrontends
      .filter((f) => [...targetIndices].some((idx) => !f.excludeTargets.has(idx)))
      .map((f) => f.id)
      .sort();
    const signature = applicable.join(",");
    const group = groups.get(signature) ?? { frontendIds: applicable, hosts: [], hostPatterns: [] };
    if (matcher.startsWith("host:")) group.hosts.push(matcher.slice("host:".length));
    else group.hostPatterns.push(matcher.slice("pattern:".length));
    groups.set(signature, group);
  }

  const frontendById = new Map(convertibleFrontends.map((f) => [f.id, f]));

  const routes: Route[] = [];
  let groupIndex = 0;
  for (const group of groups.values()) {
    const frontendIds = group.frontendIds.length > 0 ? group.frontendIds : [fallbackFrontend.id];
    const isPrimary = groupIndex === 0;
    routes.push({
      id: isPrimary ? toKebabCase(serviceKey) : `${toKebabCase(serviceKey)}-${groupIndex}`,
      name: isPrimary ? service.name : `${service.name} (additional hosts)`,
      hosts: [...new Set(group.hosts)],
      hostPatterns: [...new Set(group.hostPatterns)],
      frontends: frontendIds.map((fid) => {
        if (fid === fallbackFrontend.id) return fallbackFrontend;
        const f = frontendById.get(fid);
        if (!f)
          throw new Error(`internal error: convertible frontend '${fid}' vanished during grouping`);
        return {
          id: f.id,
          name: f.name,
          strategy: { type: "replace-origin" as const },
          instances: f.instances,
        };
      }),
    });
    groupIndex++;
  }
  return routes;
}

async function previousRevision(outputPath: string): Promise<number> {
  const file = Bun.file(outputPath);
  if (!(await file.exists())) return 0;
  try {
    const existing = (await file.json()) as { revision?: number };
    return typeof existing.revision === "number" ? existing.revision : 0;
  } catch {
    return 0;
  }
}

async function main() {
  const outputPath = process.argv[2] ?? "dist/routes.json";

  const [config, instancesData] = await Promise.all([
    fetchLibRedirectConfig(),
    fetchLibRedirectInstances(),
  ]);

  const routes: Route[] = [];
  const log = (message: string) => console.error(message);
  for (const [serviceKey, service] of Object.entries(config.services)) {
    routes.push(...convertService(serviceKey, service, instancesData, log));
  }

  const manifest: Manifest = {
    schemaVersion: 1,
    revision: (await previousRevision(outputPath)) + 1,
    generatedAt: new Date().toISOString(),
    routes,
  };

  await Bun.write(outputPath, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`Wrote ${outputPath}: ${routes.length} routes, revision ${manifest.revision}.`);
}

await main();
