#!/usr/bin/env bun
/**
 * Semantic validator for a routing manifest, mirroring the Android
 * ManifestValidator (android/core) plus the TemplateRenderer placeholder
 * grammar — the checks the JSON Schema cannot express (cross-record
 * uniqueness, strategy/instance biconditional, SSRF-guarded instance
 * origins). Exits non-zero when any error is found.
 *
 * Usage: bun run src/validate-routes.ts [manifest-path]
 * Defaults to ../android/app/src/main/assets/routes.json.
 */
import type { Manifest, Route, RouteFrontend } from "./manifest-types";

const ROUTE_ID_PATTERN = /^[a-z][a-z0-9-]{0,63}$/;
const FRONTEND_ID_PATTERN = /^[a-z][a-z0-9-]{0,63}$/;
const HOSTNAME_PATTERN =
  /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
const INSTANCE_ORIGIN_PATTERN =
  /^https:\/\/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$/;
const SCHEME_PATTERN = /^[a-z][a-z0-9+.-]{1,31}$/;
const TEMPLATE_PLACEHOLDER_PATTERN = /\{(instance|path|fragment|query:[A-Za-z0-9_]+)\}/g;
const BOUNDED_QUANTIFIER_PATTERN = /\{\d{1,2}(,\d{1,2})?\}/g;

const MAX_ROUTE_COUNT = 2000;
const MAX_NAME_LENGTH = 80;
const MAX_HOST_COUNT = 32;
const MAX_HOST_MATCHER_COUNT = 64; // per route: hosts + hostPatterns
const MAX_HOST_PATTERN_COUNT = 16;
const MAX_FRONTEND_COUNT = 16;
const MAX_INSTANCE_COUNT = 64;
const MAX_HOST_PATTERN_LENGTH = 256;
const MAX_TEMPLATE_LENGTH = 512;

const RESERVED_TLDS = new Set(["localhost", "local", "invalid", "test", "example"]);
// Mirrors ManifestValidator.isUnsafeHostPattern: the blocked backslash entry
// is the two-character sequence backslash-backslash (an escaped backslash
// has no business in a hostname pattern), not a single escape like "\.".
const HOST_PATTERN_UNSAFE_TOKENS = ["/", "\\\\", "(?<", ".*", ".+", "\\1", "\\2", "\\3"];

const INSTANCE_REQUIRING_STRATEGIES = new Set(["replace-origin", "template"]);

function hasControlCharacters(value: string): boolean {
  for (const character of value) {
    if ((character.codePointAt(0) ?? 0) < 0x20) return true;
  }
  return false;
}

function isPublicHost(host: string): boolean {
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false;
  const ipv4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (ipv4) {
    const first = Number(ipv4[1]);
    const second = Number(ipv4[2]);
    if (first === 0 || first === 10 || first === 127) return false;
    if (first === 169 && second === 254) return false;
    if (first === 172 && second >= 16 && second <= 31) return false;
    if (first === 192 && second === 168) return false;
  }
  return true;
}

function hostPatternIsInvalid(pattern: string): string | null {
  if (!pattern.startsWith("^") || !pattern.endsWith("$")) return "must be anchored with ^ and $";
  if (pattern.length > MAX_HOST_PATTERN_LENGTH)
    return `is longer than ${MAX_HOST_PATTERN_LENGTH} characters`;
  if (hasControlCharacters(pattern)) return "contains control characters";
  if (HOST_PATTERN_UNSAFE_TOKENS.some((token) => pattern.includes(token)))
    return "contains an unsafe token";
  // Braces are only allowed as bounded quantifiers ({n} or {n,m}); after
  // stripping those, any leftover brace is unsupported (mirrors the bounded
  // quantifier check in ManifestValidator).
  const withoutBoundedQuantifiers = pattern.replace(BOUNDED_QUANTIFIER_PATTERN, "");
  if (withoutBoundedQuantifiers.includes("{") || withoutBoundedQuantifiers.includes("}")) {
    return "has unsupported brace usage";
  }
  try {
    new RegExp(pattern);
  } catch {
    return "does not compile as a RegExp";
  }
  return null;
}

function templateIsInvalid(output: string): string | null {
  if (output.trim().length === 0) return "is blank";
  if (output.length > MAX_TEMPLATE_LENGTH)
    return `is longer than ${MAX_TEMPLATE_LENGTH} characters`;
  if (hasControlCharacters(output)) return "contains control characters";
  const withoutKnown = output.replace(TEMPLATE_PLACEHOLDER_PATTERN, "");
  if (withoutKnown.includes("{") || withoutKnown.includes("}")) {
    return "contains an unsupported placeholder (allowed: {instance}, {path}, {fragment}, {query:NAME})";
  }
  return null;
}

function validateInstances(frontend: RouteFrontend, where: string, errors: string[]): void {
  const instances = frontend.instances ?? [];
  if (instances.length < 1) {
    errors.push(`${where}: strategy ${frontend.strategy.type} requires at least one instance`);
    return;
  }
  if (instances.length > MAX_INSTANCE_COUNT) {
    errors.push(`${where}: too many instances (${instances.length} > ${MAX_INSTANCE_COUNT})`);
  }
  const seen = new Set<string>();
  for (const instance of instances) {
    if (seen.has(instance)) errors.push(`${where}: duplicate instance: ${instance}`);
    seen.add(instance);
    if (!INSTANCE_ORIGIN_PATTERN.test(instance)) {
      errors.push(`${where}: invalid HTTPS instance origin: ${instance}`);
      continue;
    }
    const host = instance.replace(/^https:\/\//, "").replace(/:[0-9]{1,5}$/, "");
    if (!isPublicHost(host)) errors.push(`${where}: instance host is not public: ${host}`);
    const tld = host.split(".").pop() ?? "";
    if (RESERVED_TLDS.has(tld)) errors.push(`${where}: instance host has a reserved TLD: ${host}`);
  }
}

/** Validates the manifest and returns a list of human-readable errors (empty = valid). */
export function validateRoutes(manifest: Manifest): string[] {
  const errors: string[] = [];

  if (manifest.schemaVersion !== 1)
    errors.push(`schemaVersion must be 1, got ${String(manifest.schemaVersion)}`);
  if (!Number.isInteger(manifest.revision) || manifest.revision < 1) {
    errors.push(`revision must be a positive integer, got ${String(manifest.revision)}`);
  }
  if (Number.isNaN(Date.parse(manifest.generatedAt))) {
    errors.push(`generatedAt is not a valid ISO-8601 timestamp: ${manifest.generatedAt}`);
  }
  if (manifest.routes.length > MAX_ROUTE_COUNT)
    errors.push(`too many routes: ${manifest.routes.length} > ${MAX_ROUTE_COUNT}`);

  const seenRouteIds = new Set<string>();
  const seenHosts = new Set<string>();

  for (const route of manifest.routes) {
    validateRoute(route, seenRouteIds, seenHosts, errors);
  }
  return errors;
}

function validateRoute(
  route: Route,
  seenRouteIds: Set<string>,
  seenHosts: Set<string>,
  errors: string[],
): void {
  const where = `route "${route.id}"`;
  if (seenRouteIds.has(route.id)) errors.push(`duplicate route id: ${route.id}`);
  seenRouteIds.add(route.id);
  if (!ROUTE_ID_PATTERN.test(route.id)) errors.push(`${where}: invalid route id`);
  if (!route.name || route.name.length > MAX_NAME_LENGTH) {
    errors.push(`${where}: name must be 1-${MAX_NAME_LENGTH} characters`);
  }

  const patterns = route.hostPatterns ?? [];
  if (route.hosts.length < 1 && patterns.length < 1) {
    errors.push(`${where}: needs at least one host or host pattern`);
  }
  if (route.hosts.length > MAX_HOST_COUNT) {
    errors.push(`${where}: too many hosts (${route.hosts.length} > ${MAX_HOST_COUNT})`);
  }
  if (route.hosts.length + patterns.length > MAX_HOST_MATCHER_COUNT) {
    errors.push(
      `${where}: too many host matchers (${route.hosts.length + patterns.length} > ${MAX_HOST_MATCHER_COUNT})`,
    );
  }
  for (const host of route.hosts) {
    if (seenHosts.has(host)) errors.push(`${where}: duplicate host across manifest: ${host}`);
    seenHosts.add(host);
    if (!HOSTNAME_PATTERN.test(host)) errors.push(`${where}: invalid hostname: ${host}`);
  }

  if (patterns.length > MAX_HOST_PATTERN_COUNT) {
    errors.push(
      `${where}: too many host patterns (${patterns.length} > ${MAX_HOST_PATTERN_COUNT})`,
    );
  }
  for (const pattern of patterns) {
    const problem = hostPatternIsInvalid(pattern);
    if (problem) errors.push(`${where}: host pattern ${problem}: ${pattern}`);
  }

  if (route.frontends.length < 1) errors.push(`${where}: needs at least one frontend`);
  if (route.frontends.length > MAX_FRONTEND_COUNT) {
    errors.push(`${where}: too many frontends (${route.frontends.length} > ${MAX_FRONTEND_COUNT})`);
  }
  const seenFrontendIds = new Set<string>();
  for (const frontend of route.frontends) {
    validateFrontend(frontend, where, seenFrontendIds, errors);
  }
}

function validateFrontend(
  frontend: RouteFrontend,
  where: string,
  seenFrontendIds: Set<string>,
  errors: string[],
): void {
  const front = `${where} frontend "${frontend.id}"`;
  if (seenFrontendIds.has(frontend.id)) errors.push(`${front}: duplicate frontend id in route`);
  seenFrontendIds.add(frontend.id);
  if (!FRONTEND_ID_PATTERN.test(frontend.id)) errors.push(`${front}: invalid frontend id`);
  if (!frontend.name || frontend.name.length > MAX_NAME_LENGTH) {
    errors.push(`${front}: name must be 1-${MAX_NAME_LENGTH} characters`);
  }

  const strategy = frontend.strategy;
  switch (strategy.type) {
    case "replace-origin":
    case "template":
      if (strategy.type === "template") {
        const problem = templateIsInvalid(strategy.output);
        if (problem) errors.push(`${front}: template output ${problem}: ${strategy.output}`);
      }
      validateInstances(frontend, front, errors);
      break;
    case "custom-scheme":
      if (!SCHEME_PATTERN.test(strategy.scheme))
        errors.push(`${front}: invalid custom scheme: ${strategy.scheme}`);
      if (frontend.instances && frontend.instances.length > 0) {
        errors.push(`${front}: strategy custom-scheme must not carry instances`);
      }
      break;
    case "passthrough":
      if (frontend.instances && frontend.instances.length > 0) {
        errors.push(`${front}: strategy passthrough must not carry instances`);
      }
      break;
    default: {
      errors.push(
        `${front}: unknown strategy type: ${String((strategy as { type?: unknown }).type)}`,
      );
    }
  }
}

async function main(): Promise<number> {
  const defaultPath = new URL(
    "../../android/app/src/main/assets/routes.json",
    import.meta.url,
  ).pathname.replace(/^\/([A-Za-z]:)/, "$1"); // Windows drive letters coming out of URL.pathname
  const path = process.argv[2] ?? defaultPath;
  const file = Bun.file(path);
  if (!(await file.exists())) {
    console.error(`Manifest not found: ${path}`);
    return 2;
  }
  let manifest: Manifest;
  try {
    manifest = (await file.json()) as Manifest;
  } catch (error) {
    console.error(
      `Manifest is not valid JSON: ${error instanceof Error ? error.message : String(error)}`,
    );
    return 2;
  }
  const errors = validateRoutes(manifest);
  if (errors.length > 0) {
    console.error(`Manifest failed semantic validation (${errors.length} error(s)):`);
    for (const error of errors) console.error(`  - ${error}`);
    return 1;
  }
  console.log(
    `Manifest OK: ${manifest.routes.length} routes, revision ${manifest.revision} (${path}).`,
  );
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
