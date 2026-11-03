/** Shared shape of the routing manifest (schema/routes.schema.json), mirrored from the Kotlin core model. */
export type RouteStrategy =
  | { type: "replace-origin" }
  | { type: "template"; output: string }
  | { type: "custom-scheme"; scheme: string }
  | { type: "passthrough" };

export interface RouteFrontend {
  id: string;
  name: string;
  strategy: RouteStrategy;
  instances?: string[];
}

export interface Route {
  id: string;
  name: string;
  hosts: string[];
  hostPatterns?: string[];
  frontends: RouteFrontend[];
}

export interface Manifest {
  schemaVersion: 1;
  revision: number;
  generatedAt: string;
  routes: Route[];
}
