import { describe, expect, test } from "bun:test";
import { extractHostPattern, extractHosts } from "./libredirect-config";

describe("extractHosts", () => {
  test("simple optional www prefix", () => {
    expect(extractHosts("^https?:\\/{2}(www\\.)?reuters\\.com\\/")).toEqual(
      expect.arrayContaining(["reuters.com", "www.reuters.com"]),
    );
  });

  test("optional prefix alternation with multiple choices", () => {
    const hosts = extractHosts(
      "^https?:\\/{2}(www\\.|m\\.)?youtube.com(\\/|$)(?!iframe_api\\/|redirect\\/)",
    );
    expect(hosts).toEqual(
      expect.arrayContaining(["youtube.com", "www.youtube.com", "m.youtube.com"]),
    );
    expect(hosts).toHaveLength(3);
  });

  test("required (non-optional) subdomain alternation", () => {
    const hosts = extractHosts("^https?:\\/{2}(i|s)\\.ytimg.com\\/vi\\/.*\\/..*");
    expect(hosts).toEqual(expect.arrayContaining(["i.ytimg.com", "s.ytimg.com"]));
    expect(hosts).toHaveLength(2);
  });

  test("alternation in the base domain itself", () => {
    const hosts = extractHosts(
      "^https?:\\/{2}(www\\.)?(youtube|youtube-nocookie)\\.com\\/embed\\/..*",
    );
    expect(hosts).toEqual(
      expect.arrayContaining([
        "youtube.com",
        "www.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
      ]),
    );
  });

  test("wildcard subdomain (regex character class) is unsupported, not approximated", () => {
    expect(extractHosts("^https?:\\/{2}([a-zA-Z0-9-]+\\.)*quora\\.com\\/")).toBeNull();
  });

  test("negative lookahead path exclusion is unsupported, not approximated", () => {
    expect(
      extractHosts(
        "^https?:\\/{2}((?!(api|data|blog)\\.)[a-zA-Z0-9-]+\\.(meta\\.)?)?stackexchange\\.com\\/",
      ),
    ).toBeNull();
  });

  test("regression: quantified TLD suffix must not be silently truncated into a wrong host", () => {
    // Real bug: this used to extract "translate.google" (not a real domain)
    // by dropping the (\.[a-z]{2,3}){1,2} TLD group instead of recognizing
    // it can't be resolved to a finite set.
    expect(extractHosts("^https?:\\/{2}translate\\.google(\\.[a-z]{2,3}){1,2}\\/")).toBeNull();
  });

  test("regression: onion-hash label mixed with a clearnet TLD by cross-product is filtered out", () => {
    // Real bug: (reddit|reddittorjg...ooad)\.(com|onion) naively cross-products
    // into nonsense hosts like "reddittorjg...ooad.com". Only the genuine
    // clearnet combinations should survive.
    const hosts = extractHosts(
      "^https?:\\/{2}(www\\.|old\\.)?(reddit|reddittorjg6rue252oqsxryoxengawnmo46qy4kyii5wtqnwfj4ooad)\\.(com|onion)(?=\\/r\\/|\\/?$)",
    );
    expect(hosts).toEqual(
      expect.arrayContaining(["reddit.com", "www.reddit.com", "old.reddit.com"]),
    );
    for (const host of hosts ?? []) {
      expect(host).not.toContain("onion");
      expect(host).not.toContain("reddittorjg6");
    }
  });

  test("reserved/test TLDs (.invalid, .onion, .i2p, .loki) are filtered, not treated as real hosts", () => {
    expect(extractHosts("^https?:\\/{2}search\\.libredirect\\.invalid")).toBeNull();
  });

  test("pattern without the expected https? prefix is unsupported", () => {
    expect(extractHosts("^ftp:\\/{2}example\\.com\\/")).toBeNull();
  });

  test("host with no query/path suffix (pattern ends right after the host)", () => {
    expect(extractHosts("^https?:\\/{2}bsky\\.app")).toEqual(["bsky.app"]);
  });

  test("extracts a safe hostname pattern for wildcard subdomains", () => {
    expect(extractHostPattern("^https?:\\/{2}([a-zA-Z0-9-]+\\.)*quora\\.com\\/")).toBe(
      "^([a-zA-Z0-9-]+\\.)*quora\\.com$",
    );
  });

  test("extracts hostname patterns with bounded locale TLDs", () => {
    expect(extractHostPattern("^https?:\\/{2}maps\\.google(\\.[a-z]{2,3}){1,2}\\/")).toBe(
      "^maps\\.google(\\.[a-z]{2,3}){1,2}$",
    );
  });
});
