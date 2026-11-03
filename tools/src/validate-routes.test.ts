import committedManifestJson from "../../android/app/src/main/assets/routes.json";
import { describe, expect, test } from "bun:test";
import type { Manifest, Route } from "./manifest-types";
import { validateRoutes } from "./validate-routes";

const committedManifest = committedManifestJson as unknown as Manifest;

function routeById(id: string): Route {
  const route = committedManifest.routes.find((r) => r.id === id);
  if (!route) throw new Error(`route ${id} missing from committed manifest`);
  return structuredClone(route);
}

describe("validate-routes", () => {
  test("committed manifest passes semantic validation", () => {
    expect(validateRoutes(committedManifest)).toEqual([]);
  });

  test("duplicate host across routes is rejected", () => {
    const manifest = structuredClone(committedManifest);
    const first = manifest.routes[0];
    const second = manifest.routes[1];
    if (!first || !second) throw new Error("committed manifest too small");
    second.hosts = [first.hosts[0] ?? ""];
    expect(validateRoutes(manifest).join(" ")).toContain("duplicate host");
  });

  test("passthrough frontend with instances is rejected", () => {
    const manifest = structuredClone(committedManifest);
    const route = routeById("youtube");
    route.frontends = [
      {
        id: "bad",
        name: "Bad",
        strategy: { type: "passthrough" },
        instances: ["https://example.org"],
      },
    ];
    manifest.routes = [route];
    expect(validateRoutes(manifest).join(" ")).toContain("must not carry instances");
  });

  test("replace-origin frontend without instances is rejected", () => {
    const manifest = structuredClone(committedManifest);
    const route = routeById("youtube");
    route.frontends = [{ id: "bad", name: "Bad", strategy: { type: "replace-origin" } }];
    manifest.routes = [route];
    expect(validateRoutes(manifest).join(" ")).toContain("requires at least one instance");
  });

  test("private instance host is rejected", () => {
    const manifest = structuredClone(committedManifest);
    const route = routeById("youtube");
    route.frontends = [
      {
        id: "bad",
        name: "Bad",
        strategy: { type: "replace-origin" },
        instances: ["https://192.168.1.1"],
      },
    ];
    manifest.routes = [route];
    expect(validateRoutes(manifest).join(" ")).toContain("not public");
  });

  test("unanchored host pattern is rejected", () => {
    const manifest = structuredClone(committedManifest);
    const route = routeById("youtube");
    route.hostPatterns = ["example\\.com$"];
    manifest.routes = [route];
    expect(validateRoutes(manifest).join(" ")).toContain("anchored");
  });

  test("template with unsupported placeholder is rejected", () => {
    const manifest = structuredClone(committedManifest);
    const route = routeById("youtube");
    route.frontends = [
      {
        id: "templated",
        name: "Templated",
        strategy: { type: "template", output: "{instance}/{path}?evil={eval}" },
        instances: ["https://example.org"],
      },
    ];
    manifest.routes = [route];
    expect(validateRoutes(manifest).join(" ")).toContain("unsupported placeholder");
  });

  test("template with supported placeholders is accepted", () => {
    const manifest = structuredClone(committedManifest);
    const route = routeById("youtube");
    route.frontends = [
      {
        id: "templated",
        name: "Templated",
        strategy: { type: "template", output: "{instance}/{path}?q={query:q}#{fragment}" },
        instances: ["https://example.org"],
      },
    ];
    manifest.routes = [route];
    expect(validateRoutes(manifest)).toEqual([]);
  });
});
