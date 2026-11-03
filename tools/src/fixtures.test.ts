import { readFileSync, readdirSync } from "node:fs";
import { describe, expect, test } from "bun:test";
import committedManifestJson from "../../android/app/src/main/assets/routes.json";
import type { Manifest, Route } from "./manifest-types";

/**
 * Regression tests over fixtures/*.json — real URLs previously broken or
 * confirmed against upstream behavior. Each case pins (a) the route and
 * frontend still existing in the committed manifest and (b) the input
 * URL's path/query/fragment decomposition, which is exactly what
 * replace-origin and template strategies carry over to the instance.
 */
const committedManifest = committedManifestJson as unknown as Manifest;

interface FixtureCase {
  input: string;
  frontendId: string;
  expected: { path: string; query: string | null; fragment: string | null } | "passthrough";
}

interface Fixture {
  routeId: string;
  cases: FixtureCase[];
}

const fixturesDir = new URL("../../fixtures/", import.meta.url);
const fixtureFiles = readdirSync(fixturesDir).filter((name) => name.endsWith(".json"));

describe("url regression fixtures", () => {
  test("fixtures are present", () => {
    expect(fixtureFiles.length).toBeGreaterThan(0);
  });

  for (const file of fixtureFiles) {
    const fixture = JSON.parse(
      readFileSync(new URL(`../../fixtures/${file}`, import.meta.url), "utf8"),
    ) as Fixture;

    describe(file, () => {
      const route: Route | undefined = committedManifest.routes.find(
        (r) => r.id === fixture.routeId,
      );

      test(`route "${fixture.routeId}" exists in the committed manifest`, () => {
        expect(route).toBeDefined();
      });

      for (const [index, fixtureCase] of fixture.cases.entries()) {
        test(`case ${index}: ${fixtureCase.input}`, () => {
          expect(route).toBeDefined();
          const frontend = route?.frontends.find((f) => f.id === fixtureCase.frontendId);
          expect(frontend).toBeDefined();

          const url = new URL(fixtureCase.input);
          if (fixtureCase.expected === "passthrough") {
            expect(frontend?.strategy.type).toBe("passthrough");
            return;
          }
          expect(frontend?.strategy.type).toBe("replace-origin");
          expect(url.pathname).toBe(fixtureCase.expected.path);
          const query = url.search.startsWith("?") ? url.search.slice(1) : url.search;
          expect(query.length > 0 ? query : null).toBe(fixtureCase.expected.query);
          const fragment = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
          expect(fragment.length > 0 ? fragment : null).toBe(fixtureCase.expected.fragment);
        });
      }
    });
  }
});
