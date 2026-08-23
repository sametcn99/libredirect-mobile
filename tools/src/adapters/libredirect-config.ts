/**
 * Adapter for libredirect/browser_extension's src/config.json.
 *
 * config.json's `targets` are full JS regex strings (including negative
 * lookaheads and wildcard subdomain character classes) because the browser
 * extension intercepts every network request. This app only routes
 * top-level link clicks/shares, and the manifest schema deliberately has no
 * regex at all (see the Phase 1 manifest-spec plan artifact, §1) — so
 * `extractHosts` recognizes a conservative, hand-verified subset of that
 * regex grammar (optional/alternation prefix groups, alternation in the
 * base domain, escaped dots) and returns `null` for anything outside it,
 * rather than guess. Verified empirically against every target actually
 * published in config.json as of this writing: 77 of 102 targets extract
 * cleanly; the 25 that don't are all genuine wildcard-subdomain or
 * lookahead patterns with no finite host-list equivalent (see
 * generate-routes.ts's unsupported-target report).
 */

export interface LibRedirectFrontend {
  name: string;
  instanceList?: boolean;
  embeddable?: boolean;
  desktopApp?: boolean;
  excludeTargets?: number[];
  url?: string;
}

export interface LibRedirectService {
  name: string;
  targets: string[];
  frontends: Record<string, LibRedirectFrontend>;
  options?: {
    enabled?: boolean;
    frontend?: string;
  };
}

export interface LibRedirectConfig {
  services: Record<string, LibRedirectService>;
}

const CONFIG_URL =
  "https://raw.githubusercontent.com/libredirect/browser_extension/master/src/config.json";

export async function fetchLibRedirectConfig(): Promise<LibRedirectConfig> {
  const response = await fetch(CONFIG_URL);
  if (!response.ok) {
    throw new Error(`Failed to fetch LibRedirect config.json: HTTP ${response.status}`);
  }
  return (await response.json()) as LibRedirectConfig;
}

type Token = { type: "literal"; value: string } | { type: "group"; options: string[] };

const TARGET_PREFIXES = ["^https?:\\/{2}", "^https?:\\/\\/", "^https:\\/{2}", "^https:\\/\\/"];
const HOSTNAME_RE = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
const RESERVED_TLDS = new Set(["onion", "i2p", "loki", "invalid", "test", "example", "localhost"]);
const ONION_V3_LABEL = /^[a-z2-7]{56}$/;

/**
 * Returns the finite set of hostnames a `targets` regex pattern matches, or
 * `null` if the pattern needs real regex expressiveness (wildcard
 * subdomains, lookaheads, quantified TLD suffixes, ...) this schema
 * doesn't support.
 */
export function extractHosts(pattern: string): string[] | null {
  let rest: string | null = null;
  for (const prefix of TARGET_PREFIXES) {
    if (pattern.startsWith(prefix)) {
      rest = pattern.slice(prefix.length);
      break;
    }
  }
  if (rest === null) return null;

  const tokens: Token[] = [];
  let i = 0;
  let literalBuf = "";
  const flushLiteral = () => {
    if (literalBuf.length > 0) {
      tokens.push({ type: "literal", value: literalBuf });
      literalBuf = "";
    }
  };

  while (i < rest.length) {
    const c = rest[i];
    if (c === undefined) break;

    if (c === "(") {
      const close = rest.indexOf(")", i);
      if (close === -1) break; // unbalanced -> treat what we have so far as the whole host
      const inner = rest.slice(i + 1, close);
      const isLookaroundOrNonCapturing = inner.startsWith("?");
      const options = inner.split("|");
      const hostnameSafe = options.every((o) => /^[a-z0-9\\.-]*$/i.test(o));

      if (isLookaroundOrNonCapturing) break; // never part of the host

      if (!hostnameSafe) {
        // A genuine trailing boundary group like (\/|$) is safe to stop at.
        // A group trying to EXTEND the domain (e.g. a quantified TLD suffix)
        // must not be silently truncated into a shorter, wrong host.
        const looksLikeDomainContinuation = options.some(
          (o) => o.startsWith(".") || o.startsWith("\\."),
        );
        if (looksLikeDomainContinuation) return null;
        break;
      }

      const optional = rest[close + 1] === "?";
      flushLiteral();
      const unescaped = options.map((o) => o.replace(/\\(.)/g, "$1"));
      tokens.push({ type: "group", options: optional ? [...unescaped, ""] : unescaped });
      i = close + (optional ? 2 : 1);
      continue;
    }

    if (c === "\\") {
      const next = rest[i + 1];
      if (next === undefined) break;
      if (next === "/" || next === "$") break; // escaped boundary char, not part of the host
      literalBuf += next;
      i += 2;
      continue;
    }

    if (/[a-z0-9.-]/i.test(c)) {
      literalBuf += c;
      i += 1;
      continue;
    }

    break; // any other character (/, $, ?, *, [, ...) ends the host expression
  }
  flushLiteral();

  if (tokens.length === 0) return null;

  let hosts: string[] = [""];
  for (const token of tokens) {
    if (token.type === "literal") {
      hosts = hosts.map((h) => h + token.value);
    } else {
      const next: string[] = [];
      for (const h of hosts) for (const option of token.options) next.push(h + option);
      hosts = next;
    }
  }

  const result = new Set<string>();
  for (const h of hosts) {
    const lower = h.toLowerCase();
    if (!HOSTNAME_RE.test(lower)) return null;
    const labels = lower.split(".");
    const tld = labels[labels.length - 1];
    if (tld !== undefined && RESERVED_TLDS.has(tld)) continue; // non-clearnet network
    if (labels.some((l) => ONION_V3_LABEL.test(l))) continue; // onion hash mixed into a clearnet TLD
    result.add(lower);
  }
  return result.size > 0 ? [...result] : null;
}

/**
 * Converts the hostname portion of an upstream target regex into an anchored
 * hostname-only pattern. This intentionally discards path predicates: the
 * Android router resolves links by host, while preserving the hostname regex
 * is enough to cover wildcard subdomains and locale TLDs without embedding
 * arbitrary URL regexes in the manifest.
 */
export function extractHostPattern(pattern: string): string | null {
  let rest: string | null = null;
  for (const prefix of TARGET_PREFIXES) {
    if (pattern.startsWith(prefix)) {
      rest = pattern.slice(prefix.length);
      break;
    }
  }
  if (rest === null) return null;

  // A lookahead at the beginning is part of the hostname expression (for
  // example stackexchange's API exclusion); a later lookahead is a path
  // predicate and must be removed.
  const lookahead = [...rest.matchAll(/\(\?[=!]/g)].find((match) => (match.index ?? 0) > 2);
  if (lookahead?.index !== undefined) rest = rest.slice(0, lookahead.index);

  const pathGroup = rest.search(/\((?:\\\/|\/|\$)/);
  if (pathGroup >= 0) rest = rest.slice(0, pathGroup);
  const slash = rest.search(/\\\//);
  if (slash >= 0) rest = rest.slice(0, slash);
  rest = rest.replace(/\$$/, "").trim();
  // Normalize unbounded wildcard labels to a bounded hostname label before
  // they are stored in the manifest. This keeps matching predictable and
  // avoids handing a backtracking-heavy `.*` expression to the client.
  rest = rest.replace(/\.\*/g, "[a-zA-Z0-9-]+");

  if (!rest || /(?:invalid|localhost|\.onion|\.i2p|\.loki)/i.test(rest)) return null;
  const result = `^${rest}$`;
  try {
    // Validate the syntax now so malformed upstream patterns are reported and
    // never reach the signed manifest or the Android runtime.
    new RegExp(result);
  } catch {
    return null;
  }
  return result;
}
